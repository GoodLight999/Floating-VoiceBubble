# LiteRT-LM ships JNI-backed implementations whose entry points are discovered at runtime.
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx JNI resolves classes, fields and native methods by their Java/Kotlin names.
# Renaming any of these with R8 can make release builds fail only at runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }
