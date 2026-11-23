# Vector wallpaper collection

720 genuine SVG wallpapers, totaling 7,769,698 bytes (7.77 MB).
All artwork uses native polygon paths, clipped paths, solid closing segments,
linear gradients, and circular camera outlines. No embedded raster image data.

The layouts, dimensions, padding, device profiles, and camera coordinates match
the PNG collection. The source linework and palette are reconstructed as smooth
vectors; minor differences in antialiasing, stroke geometry, and hue may occur
compared with the raster originals. Camera profiles labeled estimated remain
estimated in both formats.

SVGs scale to any resolution. Export a PNG when a wallpaper picker requires a
raster file. The generated XML is self-contained and uses no external assets.

Regenerate with `python render_vectors.py --data-root PROJECT_ROOT --output
SVG_OUTPUT_DIRECTORY`. An optional `--png-root PNG_MANIFEST_DIRECTORY` overrides
the default config/ manifest location. Requires Python, NumPy
and Pillow. Input source images are sampled only to derive palette parameters;
they are never embedded in the SVGs.
