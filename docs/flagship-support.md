# Flagship phone support

The RGB Geometry device catalog includes mainstream, gaming, and foldable flagships released from January 2020 through November 2026. The model index in `config/flagship_phones.txt` records each supported screen's native dimensions. Select a foldable's cover, inner, or other listed screen and orientation independently.

For models that share exact dimensions and the same border treatment, the `device_aliases.json` file in each pack maps the model ID to reusable artwork. The site names downloads for the selected model.

## Wallpaper fit

On Pixel and other Android phones and foldable screens, the standard RGB Border includes extra black canvas around the artwork. The frame and pattern occupy 84% of their original size. The inset is designed around the Android framework's stock 1.10 wallpaper zoom scale. Launcher cropping and wallpaper offsets can vary.

The grid fills the framed area with a narrow gap and solid edge cutoffs. The Pixel 11 Pro XL frame radius is based on Google's front-view product imagery. Other mobile frame radii are scaled to the screen size.

The RGB Camera Ring option is available for supported circular cutouts. Pixel cutouts backed by Android resource definitions are marked as published. Profiles based on product imagery or approximate cutout placement are marked as estimated, including the Pixel 11 Pro XL. The 11 Pro XL ring uses the observed wallpaper zoom for each style. The downloads include the camera-ring adjuster.

The Home Row & Search Outline on Pixel 11 Pro XL follows the supplied Pixel Launcher layout. It places a black rounded rectangle and RGB stroke behind the home row and Google Search bar. The placement is calibrated from the layout and scale seen in the screenshot. Different grid, display size, zoom, or launcher settings can affect the fit.

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

The Pixel 11 Pro XL has dedicated artwork calibrated against the observed centered 1.10× zoom on the stock launcher and lock screen. At rest, the border centerline targets roughly 16 native display pixels from each edge. The Home Row & Search Outline variant fits the full-width main frame above its separate dock outline.

Bordered patterns continue the hexagonal lattice to the frame. The frame clips any portion of the pattern outside its outline. To regenerate these results from a v1.2.1 site tree, run `src/refine_bordered_wallpapers.py --help`.
