# Add project specific ProGuard rules here.

# 基本混淆配置
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# 保留 SDK 公共 API
-keep public class com.example.drowze.DrowzeDetector {
    public *;
}

-keep public interface com.example.drowze.DrowzeDetector$DetectionListener {
    public *;
}

# MediaPipe 依赖
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# 保留模型相关类
-keep class com.google.mediapipe.tasks.vision.facelandmarker.** { *; }
-keep class com.google.mediapipe.tasks.components.containers.** { *; }
-keep class com.google.mediapipe.framework.image.** { *; }

# 保留反射相关类
-keep class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留 Android 相关类
-keep class android.content.Context { *; }
-keep class android.os.Build { *; }
-keep class android.os.Vibrator { *; }
-keep class android.os.VibrationEffect { *; }

# 防止过度混淆
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# 移除日志（可选）
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 反调试措施（简单的反调试检测）
-keep class com.example.drowze.DrowzeDetector {
    private static boolean isDebuggerAttached();
}

# 加密敏感字符串（通过混淆使字符串难以识别）
-keepclassmembers class com.example.drowze.DrowzeDetector {
    private static java.lang.String encryptString(java.lang.String);
    private static java.lang.String decryptString(java.lang.String);
}