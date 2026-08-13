package com.goodlight.floatingvoicebubble.accessibility

import android.Manifest
import android.accessibilityservice.*
import android.content.*
import android.content.pm.PackageManager
import android.view.accessibility.*
import android.view.inputmethod.EditorInfo
import com.goodlight.floatingvoicebubble.*
import com.goodlight.floatingvoicebubble.correction.*
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.*
import com.goodlight.floatingvoicebubble.overlay.FloatingBubbleController
import com.goodlight.floatingvoicebubble.speech.*
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.*

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settings: SettingsStore
    private lateinit var profiles: AppProfileStore
    private lateinit var dictionary: PersonalDictionary
    private lateinit var traces: SessionTraceStore
    private lateinit var asrModels: AsrModelStore
    private lateinit var finalModels: FinalAsrModelStore
    private lateinit var finalizer: FinalizationEngine
    private lateinit var overlay: FloatingBubbleController
    private val worker=Executors.newSingleThreadExecutor{r->Thread(r,"VoiceBubble-Finalizer")}
    private val inference=Executors.newCachedThreadPool{r->Thread(r,"VoiceBubble-Inference")}
    private val warmup=Executors.newSingleThreadExecutor{r->Thread(r,"VoiceBubble-Warmup")}
    private var input: TrackingInputMethod?=null
    private var session: SpeechRecognitionSession?=null
    private var target: Target?=null
    private var latest=""
    private var inputStarted=false
    private var imeVisible=false
    private var bubbleVisible=false
    private var generation=0L
    private var rawGeneration:Long?=null
    private var nextJob=0L
    private val pending=LinkedHashMap<Long,String>()
    private val targets=LinkedHashMap<Long,Target?>()
    private val h by lazy{android.os.Handler(mainLooper)}

    override fun onCreateInputMethod():InputMethod=TrackingInputMethod(this){v->h.post{inputChanged(v)}}.also{input=it}
    override fun onServiceConnected(){
        super.onServiceConnected()
        settings=SettingsStore(this); profiles=AppProfileStore(this); dictionary=PersonalDictionary(this)
        traces=SessionTraceStore(this); asrModels=AsrModelStore(this); finalModels=FinalAsrModelStore(this)
        finalizer=FinalizationEngine(this,settings,dictionary,traces,finalModels,inference)
        overlay=FloatingBubbleController(this,::toggle,::commitRaw,::cancel,::dismiss)
        overlay.attach(); inputStarted=input?.currentInputStarted==true; updateIme(true)
        val s=settings.load()
        asrModels.resolve(s.streamingAsrModelId)?.let{m->warmup.execute{runCatching{SherpaStreamingEngine.preload(m)}}}
        if(s.finalAsrMode==FinalAsrMode.REAZON_SPEECH) finalModels.resolve(s.finalAsrModelId)?.let{m->warmup.execute{runCatching{SherpaFinalAsrEngine.preload(m)}}}
    }
    override fun onAccessibilityEvent(e:AccessibilityEvent?){when(e?.eventType){
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED->h.post{updateIme()}
    }}
    override fun onInterrupt()=cancel()
    override fun onDestroy(){
        session?.close(); pending.clear(); targets.clear()
        if(::overlay.isInitialized)overlay.detach(); if(::dictionary.isInitialized)dictionary.close()
        worker.shutdownNow(); inference.shutdownNow(); warmup.shutdownNow(); super.onDestroy()
    }

    private fun inputChanged(v:Boolean){
        inputStarted=v
        if(!v){refresh();return}
        updateIme(true); h.postDelayed({updateIme(true)},180)
    }
    private fun updateIme(reset:Boolean=false){
        imeVisible=runCatching{windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}}.getOrDefault(false); refresh(reset)
    }
    private fun refresh(reset:Boolean=false){
        val v=inputStarted&&imeVisible; val appeared=!bubbleVisible&&v; val hidden=bubbleVisible&&!v; bubbleVisible=v
        if(::overlay.isInitialized)overlay.setInputAvailable(v,reset||appeared)
        when{
            hidden->session?.finishInput()
            v&&session==null&&pending.isEmpty()->overlay.showIdle()
            v&&session==null->overlay.showFinalizingStack(pending.values.toList())
        }
    }
    private fun toggle(){
        session?.let{overlay.showListening(latest,"発話を終了しています",pending.values.toList());it.finishInput();return}
        start()
    }
    private fun start(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){errorUi("マイク権限を許可してください。");return}
        val i=input; val c=i?.currentInputConnection; val e=i?.currentInputEditorInfo
        if(i==null||c==null||e==null||!i.currentInputStarted){errorUi("文字入力欄へカーソルを置いてください。");return}
        val pkg=e.packageName?.toString().orEmpty(); profiles.recordInputApp(pkg)
        val s=profiles.effectiveSettings(settings.load(),pkg); val model=asrModels.resolve(s.streamingAsrModelId)
        if(s.offlineMode&&model==null){errorUi("完全オフラインにはストリーミングASRモデルが必要です。");return}
        if(s.recognitionMode==RecognitionMode.SHERPA_STREAMING&&model==null){errorUi("Nemotronモデルを導入してください。");return}
        val gemma=File(s.gemmaModelPath).isFile
        if(s.correctionMode==CorrectionMode.GEMMA&&!gemma){errorUi("Gemma補正モデルを導入してください。");return}
        if(CorrectionBackendResolver.resolve(s,gemma)==CorrectionBackend.BYOK){
            if(s.byokModel.isBlank()){errorUi("補正モデルを選択してください。");return}
            runCatching{ByokEndpointResolver.resolve(s.byokEndpoint)}.onFailure{errorUi(it.message?:"API URLが不正です。");return}
        }
        if(s.finalAsrMode==FinalAsrMode.REAZON_SPEECH&&finalModels.resolve(s.finalAsrModelId)==null){errorUi("ReazonSpeechモデルを導入してください。");return}
        target=Target(i.generation,pkg,e.fieldId,e.fieldName); latest=""; overlay.showListening("","録音を開始しています",pending.values.toList())
        val token=++generation
        val created=runCatching{SpeechRecognitionSession(
            context=this,mode=s.recognitionMode,offlineRequired=s.offlineMode,autoEndpoint=s.autoStop,
            biasTerms=dictionary.topBiasTerms(),traceAudioDir=traces.audioDir,streamingModel=model,
            onPartial={t->if(token==generation&&session!=null){latest=t;overlay.showListening(t,pending=pending.values.toList())}},
            onState={st->if(token==generation&&session!=null)overlay.showListening(latest,st,pending.values.toList())},
            onComplete={o->complete(token,o)},onFailure={m->failed(token,m)}
        )}.getOrElse{errorUi(it.message?:"音声認識を初期化できませんでした。");return}
        session=created
        runCatching{created.start()}.onFailure{if(token==generation){created.close();session=null;target=null;errorUi(it.message?:"録音を開始できませんでした。")}}
    }
    private fun failed(token:Long,msg:String){
        if(token!=generation)return
        if(rawGeneration==token)rawGeneration=null
        session?.close();session=null;target=null;latest="";errorUi(msg)
    }
    private fun complete(token:Long,o:RecognitionOutcome){
        if(token!=generation)return
        latest=o.rawTranscript;session?.close();session=null
        val bypass=rawGeneration==token;if(bypass)rawGeneration=null
        val t=target;target=null
        val context=if(!bypass&&same(t))runCatching{input?.currentInputConnection?.getSurroundingText(700,300,0)?.text?.toString().orEmpty()}.getOrDefault("") else ""
        val base=settings.load();val s=t?.packageName?.let{profiles.effectiveSettings(base,it)}?:base
        val id=++nextJob;pending[id]=o.rawTranscript;targets[id]=t
        overlay.showFinalizingStack(pending.values.toList(),if(bypass)"補正せず確定しています" else "整えています")
        h.postDelayed({if(pending.containsKey(id))recover(id,t,o.rawTranscript,TimeoutException("確定処理が45秒を超えました"))},45000)
        worker.execute{try{val r=finalizer.finalize(o,context,s,bypass);mainExecutor.execute{deliver(id,t,r)}}catch(x:Throwable){mainExecutor.execute{recover(id,t,o.rawTranscript,x)}}}
    }

    private fun commitRaw(){
        session?.let{rawGeneration=generation;overlay.showListening(latest,"補正せず確定しています",pending.values.toList());it.finishInput();return}
        val e=pending.entries.firstOrNull()?:return;val t=targets.remove(e.key);if(pending.remove(e.key)==null)return
        put(t,e.value,"補正なしで入力しました")
        if(pending.isNotEmpty())overlay.showFinalizingStack(pending.values.toList())else idleAfter(650)
    }
    private fun deliver(id:Long,t:Target?,r:FinalizationResult){
        if(pending.remove(id)==null)return;targets.remove(id)
        if(!same(t)){clip(r.finalText);notice(r.finalText,"入力先が変わったためクリップボードへ保存しました",2400);return}
        val c=input?.currentInputConnection
        if(c==null){clip(r.finalText);notice(r.finalText,"入力欄が消えたためクリップボードへ保存しました",2400);return}
        if(!runCatching{c.commitText(r.finalText,1,null)}.getOrDefault(false)){clip(r.finalText);notice(r.finalText,"直接入力できずクリップボードへ保存しました",2400);return}
        val state=when{
            r.correctionBypassed->"補正なしで入力しました"
            r.correctionError!=null&&r.correctionChanged->"一部補正: ${short(r.correctionError)}"
            r.correctionError!=null->"補正失敗: ${short(r.correctionError)} — 認識結果を入力しました"
            !r.correctionAccepted->"安全ガードが変更を拒否しました: ${r.correctionDecisionReason?:"edit-budget"}"
            r.correctionAttempted&&r.correctionChanged->"補正して入力しました"
            r.correctionAttempted->"補正モデルは変更なしでした"
            r.finalAsrError!=null->"最終認識を使えずリアルタイム認識で入力しました"
            else->"入力しました"
        }
        val delay=if(r.correctionError!=null||!r.correctionAccepted)2400 else if(r.correctionAttempted&&!r.correctionChanged)1800 else 650
        notice(r.finalText,state,delay)
    }
    private fun put(t:Target?,text:String,state:String){
        val ok=same(t)&&(input?.currentInputConnection?.let{runCatching{it.commitText(text,1,null)}.getOrDefault(false)}==true)
        if(ok)notice(text,state,650)else{clip(text);notice(text,"$state（クリップボードへ保存）",2400)}
    }
    private fun recover(id:Long,t:Target?,text:String,x:Throwable){
        if(pending.remove(id)==null)return;targets.remove(id)
        val ok=same(t)&&(input?.currentInputConnection?.let{runCatching{it.commitText(text,1,null)}.getOrDefault(false)}==true)
        val d=x.message?.takeIf(String::isNotBlank)?.let(::short)
        if(ok)notice(text,d?.let{"補正処理エラー: $it — 認識結果を入力"}?:"補正処理を完了できず認識結果を入力",2400)
        else{clip(text);notice(text,d?.let{"確定処理エラー: $it — クリップボードへ保存"}?:"確定処理エラーのためクリップボードへ保存",2400)}
    }
    private fun notice(text:String,state:String,delay:Long){
        if(!bubbleVisible)return
        when{session!=null->overlay.showListening(latest,pending=pending.values.toList());pending.isNotEmpty()->overlay.showFinalizingStack(pending.values.toList(),state);else->{overlay.showFinalizing(text,state);idleAfter(delay)}}
    }
    private fun cancel(){
        session?.let{++generation;rawGeneration=null;it.close();session=null;target=null;latest="";if(pending.isEmpty())overlay.showIdle()else overlay.showFinalizingStack(pending.values.toList(),"前の発話を処理しています");return}
        pending.clear();targets.clear();latest="";if(::overlay.isInitialized)overlay.showIdle()
    }
    private fun dismiss(){++generation;rawGeneration=null;session?.close();session=null;target=null;latest="";pending.clear();targets.clear()}
    private fun clip(t:String)=getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble",t))
    private fun idleAfter(ms:Long)=h.postDelayed({when{session!=null||!bubbleVisible->Unit;pending.isNotEmpty()->overlay.showFinalizingStack(pending.values.toList(),"前の発話を処理しています");else->overlay.showIdle()}},ms)
    private fun errorUi(m:String){if(::overlay.isInitialized){overlay.showError(m);idleAfter(2400)}}
    private fun same(t:Target?):Boolean{
        t?:return false;val i=input?:return false;if(!i.currentInputStarted||i.generation!=t.generation)return false;val e=i.currentInputEditorInfo?:return false
        return t.packageName==e.packageName?.toString().orEmpty()&&t.fieldId==e.fieldId&&t.fieldName==e.fieldName
    }
    private fun short(s:String)=s.replace(Regex("\\s+")," ").trim().take(92)
    private data class Target(val generation:Long,val packageName:String,val fieldId:Int,val fieldName:String?)
    private class TrackingInputMethod(service:AccessibilityService,val changed:(Boolean)->Unit):InputMethod(service){
        var generation=0L;private set
        override fun onStartInput(a:EditorInfo,restarting:Boolean){if(!restarting)generation++;super.onStartInput(a,restarting);changed(true)}
        override fun onFinishInput(){super.onFinishInput();changed(false)}
    }
}