# Add project specific ProGuard rules here.

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep model classes
-keep class com.google.mediapipe.tasks.vision.facelandmarker.** { *; }
-keep class com.google.mediapipe.tasks.components.containers.** { *; }