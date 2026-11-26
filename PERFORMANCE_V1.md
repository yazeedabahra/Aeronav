# Performance V1 — experimental

Goal: reduce camera conversion and UI load on low-end Android devices.

- CameraX direct Bitmap conversion; no JPEG encode/decode in the active service.
- One synchronous MediaPipe VIDEO inference at a time on the camera worker.
- Adaptive frame pacing (initial target 10 FPS for low-RAM devices, ~15 FPS otherwise).
- Requested camera sizes 240x320 / 360x480; actual size depends on device.
- One pending main-thread result; reject results older than 500 ms.
- One speed-adaptive pointer filter, rather than two fixed smoothers.
- Guard duplicate startup and serialize native model shutdown.

No new dependencies. Original gesture mapping and CPU delegate retained. The unused HandLandmarkerHelper path is not changed.

Validation: standalone Kotlin checks of pacing and pointer filter passed. Android build and hardware tests are outstanding. Run `./gradlew testDebugUnitTest assembleDebug` on Android SDK 35/JDK 17. Verify asset hand_landmarker.task is present. Measure capture-to-pointer latency, frame cadence, PSS, thermal status, false gestures and stop/start stability on actual low-end devices before release. Test screen rotations and front-camera handedness. No measured performance improvement is claimed yet.
