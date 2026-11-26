# ✋ Yvora — Touchless Hand Gesture Control

A native Android application that lets you control your entire phone using only hand gestures detected by the front-facing camera. No touch needed. No wearable. No special hardware.

---

## 📖 About

**Yvora** allows you to navigate your Android device completely hands-free. The app places a floating red dot on your screen that follows your hand position in real time. You can use specific finger gestures to tap, scroll, swipe, and navigate across any app on your phone including the home screen, Instagram, YouTube, TikTok, browser, settings, and everything else. It operates locally on the device with no internet connection required.

## ✨ Features

- **📷 Real-Time Hand Detection**: Uses the front camera and MediaPipe with adaptive frame pacing; actual speed depends on the device.
- **🎯 Dynamic Floating Pointer**: A colored pointer overlay tracks your hand and provides visual feedback.
- **👌 Pinch to Tap**: Bring your index and thumb together to instantly tap anywhere on the screen.
- **⬆️⬇️ Scroll Gestures**: Use single and double-finger gestures to scroll up or down (Right Hand).
- **⬅️➡️ Swipe Gestures**: Use single and double-finger gestures to swipe left or right (Left Hand).
- **🔋 Background Execution**: Runs as a persistent foreground service, letting you control other apps seamlessly.
- **🔒 Fully Offline**: No camera footage is stored or transmitted. Completely private.

## Downloads

Yvora is under development. No verified Yvora APK has been published yet.

---

## 🛠️ Installation

### Prerequisites

Before you begin, ensure you have the following installed:
- [Android Studio](https://developer.android.com/studio)
- Minimum Android API 24 (Android 7.0) device
- Target Android API 35 (Android 15)

### Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/yazeedabahra/Aeronav.git
   cd Aeronav
   git checkout performance-v1
   ```

2. **Add MediaPipe Model**:
   - Ensure the `hand_landmarker.task` file (~25MB) is included in the project assets.

### Running the App

To run the application on your connected Android device:

1. Open the project in Android Studio.
2. Build and run the app.
3. Grant **Camera Permission** when prompted.
4. Enable **Overlay Permission** to allow the floating pointer to draw over other apps.
5. Enable the **Accessibility Service** for Yvora in Android Settings to allow gesture injection.

---

## ⚙️ How It Works

### Architecture
Yvora is built around a single core service (`YvoraService`) that runs independently of the UI.

- **CameraX Overlay**: A 1x1 pixel invisible window keeps the camera alive even when in the background.
- **MediaPipe Pipeline**: Processes frames in `VIDEO` mode on the camera worker to output 21 hand landmarks.
- **GestureRecognizer**: Analyzes landmark positions to classify hand states and update the pointer.
- **AccessibilityService**: Injects taps and 250ms swipes system-wide that are indistinguishable from physical touches.

### Hand Tracking System
- **Landmark Detection**: Tracks 21 3D points on the hand (e.g., Wrist = 0, Thumb tip = 4, Index tip = 8).
- **Extension Logic**: A finger is considered extended if its tip is farther from the wrist than both its MCP and PIP joints.
- **Gesture Confirmation**: Gestures require a 400ms steady hold before firing, followed by a 1000ms cooldown to prevent accidental rapid firing.

---

## 📦 Dependencies

| Package / API | Purpose |
| :--- | :--- |
| `Kotlin` | Core programming language |
| `CameraX` | Image analysis and camera lifecycle |
| `MediaPipe Hand Landmarker` | Real-time 3D hand tracking |
| `AccessibilityService` | System-wide touch event injection |
| `WindowManager` | Drawing the floating pointer overlay |

---

## 📂 Project Structure

```text
app/src/main/
├── java/.../
│   ├── YvoraService.kt              # Core service — owns camera, MediaPipe, pointer
│   ├── GestureRecognizer.kt             # Classifies hand pose every frame
│   ├── YvoraAccessibilityService.kt # Injects tap and swipe gestures system-wide
│   ├── HandLandmarkerHelper.kt          # Converts camera frames and runs MediaPipe
│   └── MainActivity.kt                  # User interface — start, stop, permissions
└── res/                                 # UI layouts and resources
```

---

## 🚀 Future Enhancements

- **🔊 Voice Feedback**: Audio confirmation of fired gestures.
- **⚙️ Settings UI**: Adjust sensitivity thresholds and gesture mappings.
- **✋ Left-Handed Mode**: Explicit toggle instead of auto-detection.
- **🌞 Hardware Controls**: Brightness and volume control via gestures.
- **🤏 Advanced Gestures**: Pinch-to-zoom, double tap, and notification shade pull-down.
- **👥 Multi-Hand Detection**: Support for tracking both hands simultaneously.

---


## Android 13+ Restriction

On Android 13 and above, sideloaded APKs (installed from
a file rather than the Play Store) are restricted from
accessing Accessibility Services by default.

If Yvora does not appear in Accessibility Settings or
shows a "Restricted setting" error, follow these steps:

1. Go to Settings → Apps
2. Find Yvora in the app list
3. Tap the three-dot menu (⋮) in the top right
4. Tap "Allow restricted settings"
5. Confirm with your PIN, password, or fingerprint
6. Return to Accessibility Settings
7. Yvora should now appear and can be toggled ON

This is a one-time step required by Android for security.


---

## 📜 License

MIT License


## Attribution

Yvora is a modified fork of [AeroNav](https://github.com/omhujband/Aeronav), distributed under the original MIT license. See [LICENSE](LICENSE) for the retained copyright and license notice.

Build a branded experimental APK with `./gradlew :app:packageYvoraDebug`. The copy is written to `app/build/outputs/yvora/Yvora-debug.apk` after a successful Android build. The application ID is `com.yvora.app`; permissions must be granted separately from AeroNav.
