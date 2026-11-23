# RGB Geometry Live for Android

An Android 13+ live wallpaper with all four RGB Geometry patterns. The frame, camera ring, home row and search outline, and status bar outline use the Pixel 11 Pro XL calibration from the static pack. The app lets you toggle each outline and set a 6–60 second color cycle. Switching animation off restores the original colors.

Open **RGB Geometry Live** to configure the artwork, then tap **Apply live wallpaper** to open Android’s wallpaper preview. The same settings are used wherever Android applies this wallpaper. The display fit is tuned for the Pixel 11 Pro XL. Other displays may show black margins.

A hardware canvas runs a hue-rotation shader at about 30 FPS while the wallpaper is visible. Geometry and black space stay fixed. The app uses local assets and requests no permissions.

## Build

From the repository root, install a JDK, Android SDK Platform 36 and Build Tools 36.0.0, Python 3 with Pillow and WebP support, Inkscape, and zip. Set `ANDROID_SDK_ROOT` to your SDK path and run:

```sh
bash android-live/build.sh
```

The build renders assets from `renders/svg/` using `config/camera_profiles_additions.json` and produces `android-live/build/rgb-geometry-live-aligned.apk`. Sign the aligned APK with Android SDK's `apksigner` and your own keystore before installing. Keep the same signing key for future app updates.
