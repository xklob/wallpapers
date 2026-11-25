# Flagship phone support

The RGB Geometry device catalog includes mainstream, gaming, and foldable flagships released from January 2020 through November 2026. The model index in `config/flagship_phones.txt` records each supported screen's native dimensions. Select a foldable's cover, inner, or other listed screen and orientation independently.

For models that share exact dimensions and the same border treatment, the `device_aliases.json` file in each pack maps the model ID to reusable artwork. The site names downloads for the selected model.

## Display specifications

The 370 named phones and foldables have 438 cataloged panels. The Pixel Tablet and seven shared iPad size groups bring the total to 446. The iPad groups cover 25 individually sourced models. Each panel size is the manufacturer's full rectangular pixel resolution. The usable area follows the panel's physical corners and cutouts.

The picker exposes 603 choices. Of those, 589 are named model and orientation entries, including separate portrait and landscape choices for tablets and foldables. Six more are shared or regional device names. The remaining eight offer seven desktop resolutions and a generic 1080 × 2400 Galaxy profile.

Run `python src/validate_display_specs.py` to compare the manufacturer dimensions, model index, dropdown aliases, and catalog artwork dimensions. The per-model records link to product specifications, device overlays, archived manufacturer pages, and manufacturer-issued datasheets. [Display specification files](../config/)

## Wallpaper fit

Google phone and foldable RGB Border downloads compensate for the stock centered 1.10× Android wallpaper crop. The frame follows the panel with a two-native-pixel outer clearance. The app removes the static crop compensation on live wallpaper surfaces. Other Android launchers or zoom settings can position a downloaded wallpaper differently.

The Google catalog covers 25 distinct panels. The Google device overlays provide resolution-matched perimeters for 23, including the Pixel Tablet. Both the Pixel 10 Pro Fold and 11 Pro Fold cover overlays describe a taller 2424-pixel panel, so their frames use the stock 115-pixel corner radii on the published 2364- and 2342-pixel canvases. The source versions and numeric paths are in the [Google display specifications](../config/google_display_specs.json). Regenerate the phone and foldable frames with `uv run --script src/fit_google_displays.py`.

The RGB Camera Ring option is available for supported circular cutouts. Pixel cutouts backed by Android resource definitions are marked as published. The Pixel 10 and 11 series use the camera cutouts from Google’s stock vendor overlays. Each profile records the physical radius and center. The RGB ring extends eight native pixels beyond the cutout. The Pixel 11 Pro XL also uses the observed wallpaper zoom for each style. Other camera placements derived from product imagery are marked as estimated. The downloads include the camera-ring adjuster.

The Home Row & Search Outline on Pixel 11 Pro XL follows the supplied Pixel Launcher layout. It places a black rounded rectangle and RGB stroke behind the home row and Google Search bar. The placement is calibrated from the layout and scale seen in the screenshot. Different grid, display size, zoom, or launcher settings can affect the fit.

The Pixel Tablet RGB Border follows Google's 2560 × 1600 display outline in landscape and rotates the same curve into portrait. The colored band begins 3 native pixels inside the visible edge and is 9 pixels wide. [Pixel Tablet display specifications](https://support.google.com/googlepixeltablet/answer/13555146?hl=en) · [Google's display outline](https://android.googlesource.com/device/google/tangorpro/+/383c2db8399c0364122e57b1944fbca11f67720a/tangorpro/overlay/frameworks/base/core/res/res/values/config.xml)

On the Pixel Tablet Home screen, the optional outline fits the search bar and six icons side by side in landscape. The portrait placement is estimated from a stock Pixel Launcher screenshot showing search above the icons. Both options work with the original and RGB Border styles. Lock uses the corresponding artwork with the launcher area clear of outlines. [Portrait layout reference](https://www.thurrott.com/mobile/android/284925/google-pixel-tablet-review)

## Manufacturer references

- [Apple model identification and specifications](https://support.apple.com/en-us/108044)
- [Google Pixel hardware specifications](https://support.google.com/pixelphone/answer/7158570) and [earlier models](https://support.google.com/pixelphone/answer/16043605)
- [Samsung Galaxy S resolutions](https://developer.samsung.com/galaxy-emulator-skin/galaxy-s.html) and [Galaxy foldable specifications](https://www.samsung.com/us/support/answer/ANS10003235/)
- [OnePlus technical specifications](https://www.oneplus.com/global/support/spec)
- [Xiaomi smartphone specifications](https://www.mi.com/global/product-list/phone/)
- [Sony Xperia specifications](https://www.sony.com/electronics/support/mobile-phones-tablets-mobile-phones)
- [ASUS phones](https://www.asus.com/us/mobile-handhelds/phones/all-series/) and [ROG phone specifications](https://rog.asus.com/phones/)
- [Motorola device specifications](https://en-us.support.motorola.com/)
- [OPPO phones](https://www.oppo.com/en/smartphones/) and [Find X10 series](https://www.oppo.com/en/articles/find-x10-series/find-x10-detail/)
- [vivo model comparison](https://www.vivo.com/sg/product/productCompare)
- [HONOR Magic](https://www.honor.com/global/phones/honor-magic-series/) and [foldable specifications](https://developer.honor.com/cn/docs/adaptation_guide/guides/foldable_device_specifications)
- [HUAWEI phones](https://consumer.huawei.com/en/phones/)
- [Nothing phone product support](https://support.nothing.tech/hc/en-us/)
- [realme product specifications](https://www.realme.com/global/)
- [REDMAGIC devices](https://global.redmagic.gg/)
- [Android's wallpaper zoom scale](https://android.googlesource.com/platform/frameworks/base/+/android16-release/core/res/res/values/config.xml)

## RGB frames

The Pixel 11 Pro XL has dedicated artwork calibrated against the observed centered 1.10× zoom on the stock launcher and lock screen. At rest, the border centerline targets roughly 6 native display pixels from each edge. The Home Row & Search Outline variant continues the border around the full display and masks the hexagons only inside the dock outline.

Bordered patterns continue the hexagonal lattice to the frame. The frame clips any portion of the pattern outside its outline. To regenerate these results from a v1.2.1 site tree, run `src/refine_bordered_wallpapers.py --help`.

The Pixel 11 Pro XL Status Bar Outline clears the wallpaper behind the time, notification icons, and system indicators. It can be combined with either or both of RGB Camera Ring and Home Row & Search Outline. Its placement uses the same wallpaper crop assumptions as the other Pixel Launcher profiles.
