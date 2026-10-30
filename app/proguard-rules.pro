-keep class com.leap8.qairt.** { *; }
# Reflection/JNI entrypoints of the independent b11982 Hexagon process.
-keep class com.leap8.qairt.HexagonModelBundle { public static *; }
-keep class com.leap8.qairt.HexagonEssayBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.leap8.llama.** { *; }
