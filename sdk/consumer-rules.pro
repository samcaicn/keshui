# Consumer rules for Drowze SDK

# MediaPipe dependencies
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep vision task classes
-keep class com.google.mediapipe.tasks.vision.** { *; }