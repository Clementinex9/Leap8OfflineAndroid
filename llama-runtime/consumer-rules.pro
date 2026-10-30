# JNI resolves these symbols by name, including the progress callback on lambda classes.
-keep class com.leap8.llama.LlamaNative { native <methods>; }
-keep interface com.leap8.llama.LlamaProgressListener { *; }
-keepclassmembers class * implements com.leap8.llama.LlamaProgressListener {
    public void onProgress(byte[], int, int);
}
