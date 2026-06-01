-keep class com.rokuonsumm.data.db.** { *; }
-keep class com.rokuonsumm.transcription.TranscriptionWorker

# ONNX Runtime は JNI + リフレクションを使うので難読化から除外
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
