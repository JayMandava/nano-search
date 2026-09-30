# Native code looks these up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class ai.nanosearch.launcher.NativeLlm { *; }
-keep class ai.nanosearch.launcher.NativeLlmGpu { *; }
-keep class ai.nanosearch.launcher.NativeWhisper { *; }
-keep interface ai.nanosearch.launcher.NativeLlm$TokenListener { *; }
-keep class * implements ai.nanosearch.launcher.NativeLlm$TokenListener { *; }

# ONNX Runtime calls back into Java from its native library.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
