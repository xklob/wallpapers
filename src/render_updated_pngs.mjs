import { readFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(import.meta.url);
const sharp = require(process.env.WALLPAPER_SHARP_MODULE || 'sharp');
const plan = JSON.parse(await readFile(join(root, 'config/refresh_png.json'), 'utf8'));
for (let offset = 0; offset < plan.length; offset += 4) {
  await Promise.all(plan.slice(offset, offset + 4).map(async ({path, width, height}) => {
    const dest = join(root, 'renders/png', path.replace(/\.svg$/, '.png'));
    await mkdir(dirname(dest), {recursive: true});
    const result = await sharp(join(root, 'renders/svg', path))
      .flatten({background: '#000'})
      .removeAlpha()
      .png({compressionLevel: 6})
      .toFile(dest);
    if (result.width !== width || result.height !== height || result.channels !== 3) {
      throw new Error('Incorrect PNG dimensions or channels for ' + path);
    }
  }));
}
console.log('Rendered ' + plan.length + ' RGB PNGs.');
