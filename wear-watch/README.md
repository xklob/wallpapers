# RGB Honeycomb for Wear OS

A round, black-and-RGB face for the Pixel Watch 3 in both sizes. The interactive face shows the time, date, live heart rate, and watch battery level in Space Grotesk with a continuous 10-second full-face hue shift. Always-on mode shows the dimmed time rotating through the RGB spectrum each minute. Wear OS draws the face using Watch Face Format v4.

## Install

Enable **ADB debugging** and **Wireless debugging** in the watch’s developer options. Connect the watch and your computer to the same Wi-Fi network. With [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) installed, use the IP address and pairing port shown under **Pair new device**. Then connect with the separate port on the main Wireless debugging screen.

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUG_PORT
adb -s WATCH_IP:DEBUG_PORT install -r RGB-Honeycomb-Pixel-Watch-3.apk
```

Long-press the current watch face on the watch, add a face, and select **RGB Honeycomb**. See [Wear OS wireless debugging](https://developer.android.com/training/wearables/get-started/debug-wifi) for pairing details.

## Build

Use Android SDK platform 36, Build Tools 36.0.0 and Python with Pillow and NumPy. Download the [Space Grotesk variable font](https://raw.githubusercontent.com/google/fonts/main/ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf), then run:

```sh
bash wear-watch/build.sh /path/to/SpaceGrotesk.ttf
```

The script generates the animated hue-shifting artwork, icons, and picker thumbnail and outputs `RGB-Honeycomb-unsigned.apk`. Sign it with the Android SDK's `apksigner` before installing. The Space Grotesk license is included in the package.
