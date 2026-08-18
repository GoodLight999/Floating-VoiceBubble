package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPostProcessorTest {
    @Test
    fun commaOnlyLongJapaneseGetsMultipleSmartBreaksWithoutChangingCharacters() {
        val raw = "今日は音声入力アプリの補正を試していて、聞き取りミスの修復も確認したいし、長く話したときの読みやすさも大事で、特に句点がほとんど入らない認識結果でも、話題の区切りに合わせて適宜改行してほしいし、さらに長く続く場合は一回だけではなく複数回ちゃんと段落を分けてほしい"
        val output = CorrectionPostProcessor.apply(
            raw,
            raw,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.SMART),
        )

        assertTrue(output.count { it == '\n' } >= 2)
        assertEquals(raw, output.replace("\n", ""))
    }

    @Test
    fun punctuationFreeLongJapaneseStillGetsMultipleSmartBreaks() {
        val raw = "これは句読点がほとんど入らない音声認識結果を想定した長い日本語の発話でありユーザーが適宜改行を選んだならモデルが改行を返さなかった場合でもアプリ側で読みやすい長さへ分割される必要がありさらに一度だけ改行して残りが巨大な段落になる挙動も避けなければならないので十分に長い入力では複数の改行が必要になる"
        val output = CorrectionPostProcessor.apply(
            raw,
            raw,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.SMART),
        )

        assertTrue(output.count { it == '\n' } >= 2)
        assertEquals(raw, output.replace("\n", ""))
    }

    @Test
    fun oneModelNewlineDoesNotSuppressFurtherParagraphization() {
        val first = "最初の話題についてかなり長く説明していて、ここにも読点があり、まだ同じ段落の内容が続いているので追加の改行候補が必要になる"
        val second = "次の話題についてもかなり長く説明していて、ここにも読点があり、さらに内容が続いているので一つのモデル改行だけで処理を終えてはいけない"
        val modelOutput = "$first\n$second"
        val output = CorrectionPostProcessor.apply(
            modelOutput,
            modelOutput,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.SMART),
        )

        assertTrue(output.count { it == '\n' } >= 3)
        assertEquals(first + second, output.replace("\n", ""))
    }

    @Test
    fun smartSpacedUsesBlankLineSeparators() {
        val raw = "最初の説明をかなり長く続けていて、ここで少し話題が変わり、さらに次の内容について説明を続けていて、文章全体が十分長くなったので空行を伴う段落分けが必要になるし、最後まで読みやすい形にしたい"
        val output = CorrectionPostProcessor.apply(
            raw,
            raw,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.SMART_SPACED),
        )

        assertTrue(output.contains("\n\n"))
        assertFalse(output.replace("\n\n", "").contains('\n'))
        assertEquals(raw, output.replace("\n", ""))
    }

    @Test
    fun shortUtteranceIsNotForcedApart() {
        val raw = "今日は晴れだから散歩する"
        val output = CorrectionPostProcessor.apply(
            raw,
            raw,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.SMART),
        )
        assertEquals(raw, output)
    }

    @Test
    fun disabledLineBreakModePreservesLongText() {
        val raw = "長い文章だけれど、ユーザーが改行しないを選んでいるので、どれだけ長くてもアプリ側で勝手に改行を追加してはいけないという契約を守る"
        val output = CorrectionPostProcessor.apply(
            raw,
            raw,
            CorrectionPreferences(addPeriods = false, lineBreakMode = LineBreakMode.NONE),
        )
        assertEquals(raw, output)
    }
}
