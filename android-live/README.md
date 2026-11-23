# RGB Geometry Live for Android

An Android 13+ live wallpaper with all four RGB Geometry patterns. The frame, camera ring, home row and search outline, and status bar outline use the Pixel 11 Pro XL calibration from the static pack. The app lets you toggle each outline and set a 6–60 second color cycle. Switching animation off restores the original colors.

Open **RGB Geometry Live** to configure the artwork, then tap **Apply live wallpaper** to open Android’s wallpaper preview. The same settings are used wherever Android applies this wallpaper. The display fit is tuned for the Pixel 11 Pro XL. Other displays may show black margins.

A hardware canvas runs a hue-rotation shader at about 30 FPS while the wallpaper is visible. Geometry and black space stay fixed. The app uses local assets.

## Audio Sync preview

With **Animate colors** on, enable **Audio Sync** and grant Android's audio permission. **Device audio** uses Android's output-mix FFT. **Microphone** follows ambient sound. Tap **Start microphone** in the app to arm that session and **Stop microphone** to end it. The microphone is released while the wallpaper and preview are hidden, and the session never saves audio. Silence fades back to the normal animation. Audio Sync is off by default.

Look for **Device audio detected** or **Microphone audio detected** in the app, then open **Tune audio effects**. Choose Pulse + color, Brightness pulse, Color acceleration, or Diagonal color wave. Intensity sets the effect strength, Smoothness sets its rise and decay, and Bass sensitivity sets how readily a kick triggers it. Changes apply to the preview and active wallpaper. Reset affects only the tuner.

Output-mix capture was confirmed on a Pixel over standard playback and Bluetooth. The microphone mode and lock-screen capture still need testing on the device.

## Build

From the repository root, install a JDK, Android SDK Platform 36 and Build Tools 36.0.0, Python 3 with Pillow and WebP support, Inkscape, and zip. Set `ANDROID_SDK_ROOT` to your SDK path and run:

```sh
bash android-live/build.sh
```

The build renders assets from `renders/svg/` using `config/camera_profiles_additions.json` and produces `android-live/build/rgb-geometry-live-aligned.apk`. Sign the aligned APK with Android SDK's `apksigner` and your own keystore before installing. Keep the same signing key for future app updates.
