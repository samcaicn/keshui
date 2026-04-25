# Drowze SDK

疲劳驾驶检测 SDK，提供简洁的 API 供集成。

## 快速开始

### 添加依赖

```gradle
implementation project(':sdk')
```

### 使用示例

```kotlin
val detector = DrowzeDetector(context)
detector.setListener(object : DrowzeDetector.DetectionListener {
    override fun onDrowsy(duration: Long) {
        // 疲劳检测触发
        detector.vibrate()
    }

    override fun onAlert(message: String) {
        // 状态更新
    }

    override fun onError(error: String) {
        // 错误处理
    }
})

detector.initialize()

// 处理每一帧图像
detector.detect(bitmap)

// 释放资源
detector.release()
```

## API

### DrowzeDetector

#### 初始化
```kotlin
fun initialize(modelPath: String = "face_landmarker.task")
```

#### 检测
```kotlin
fun detect(bitmap: Bitmap)
```

#### 振动
```kotlin
fun vibrate(pattern: LongArray = longArrayOf(0, 500, 500))
fun stopVibration()
```

#### 释放
```kotlin
fun release()
```

### DetectionListener

```kotlin
interface DetectionListener {
    fun onDrowsy(duration: Long)  // 疲劳状态触发
    fun onAlert(message: String)   // 状态更新
    fun onError(error: String)     // 错误信息
}
```

## 权限

- `CAMERA` - 相机权限（必需）
- `VIBRATE` - 振动权限

## 最低版本

- minSdk: 24 (Android 7.0)
- targetSdk: 33 (Android 13)
