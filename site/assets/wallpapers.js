const byId = id => document.getElementById(id);
const collection = byId('collection');
const pattern = byId('pattern');
const patternGrid = byId('pattern-grid');
const group = byId('device-group');
const manufacturer = byId('manufacturer');
const screen = byId('screen');
const style = byId('style');
const camera = byId('camera');
const appearance = byId('appearance');
const preview = byId('preview');
const png = byId('png');
const svg = byId('svg');
const status = byId('status');
let themes = [];
let selected;

const label = value => value.split('_').map(word => {
  const special = { ipad: 'iPad', iphone: 'iPhone', rgb: 'RGB', xl: 'XL', oppo: 'OPPO',
    huawei: 'HUAWEI', oneplus: 'OnePlus', honor: 'HONOR', fhd: 'FHD', '4k': '4K' };
  return special[word] ?? word.charAt(0).toUpperCase() + word.slice(1);
}).join(' ');

const manufacturerNames = {
  iphone: 'Apple',
  ipad: 'Apple',
  pixel: 'Google',
  google: 'Google',
  honor: 'HONOR',
  oneplus: 'OnePlus',
  oppo: 'OPPO'
};

function manufacturerFor(file) {
  const [, category, brand] = file.split('/');
  return category === 'desktop' ? 'Generic' : manufacturerNames[brand] ?? label(brand);
}

function devices(active) {
  const presets = new Map();
  for (const [file, , id, family, width, height] of active.files) {
    const manufacturer = manufacturerFor(file);
    presets.set(id, { id, preset: id, family, manufacturer, width, height, name: label(id) });
  }
  const aliases = (active.deviceAliases ?? []).map(({ id, preset, name }) => ({
    ...presets.get(preset), id, name, shared: true
  }));
  return [...aliases, ...presets.values()];
}

function options(select, entries, fallback) {
  const before = select.value;
  select.replaceChildren(...entries.map(([id, text]) => new Option(text, id)));
  if (entries.some(([id]) => id === before)) select.value = before;
  else if (entries.some(([id]) => id === fallback)) select.value = fallback;
}
function theme() {
  return themes.find(item => item.id === collection.value);
}
function updatePatternGallery(active) {
  const cards = [];
  for (const [design, name] of Object.entries(active.designs)) {
    const originals = active.files.filter(row =>
      row[1] === design && row[6] === 'original' && !row[7]);
    const sample = originals.find(row => row[3] === 'Desktop' && row[4] === 1920 && row[5] === 1080)
      ?? originals.find(row => row[3] === 'Desktop') ?? originals[0];
    if (!sample) continue;

    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'pattern-card';
    card.value = design;

    const image = document.createElement('img');
    image.src = '/art/' + active.id + '/' + sample[0];
    image.alt = '';
    image.width = sample[4];
    image.height = sample[5];
    image.decoding = 'async';

    const caption = document.createElement('span');
    caption.textContent = name;
    card.append(image, caption);
    card.addEventListener('click', () => {
      pattern.value = design;
      updatePreview();
    });
    cards.push(card);
  }
  patternGrid.replaceChildren(...cards);
  byId('patterns').hidden = !cards.length;
}
function updateCollections() {
  const active = theme();
  options(pattern, Object.entries(active.designs), '02_hexagons');
  updatePatternGallery(active);
  options(group, [...new Set(active.files.map(row => row[3]))].map(name => [name, name]), 'Phones');
  byId('archive').href = '/' + active.archive;
  byId('archive').download = active.archive;
  byId('archive').hidden = false;
  const release = byId('release');
  if (active.pngArchive) {
    release.href = active.pngArchive;
    release.textContent = 'Download PNG Pack';
  } else {
    release.href = 'https://github.com/xklob/wallpapers/releases';
    release.textContent = 'View PNG Releases';
  }
  byId('pack-detail').textContent = active.packNote ?? (active.files.length + ' wallpapers per format.');
  updateManufacturers();
}
function updateManufacturers() {
  const manufacturers = new Set(devices(theme())
    .filter(device => device.family === group.value)
    .map(device => device.manufacturer));
  options(manufacturer, [...manufacturers].sort().map(name => [name, name]), 'Google');
  updateScreens();
}
function updateScreens() {
  const presets = devices(theme()).filter(device =>
    device.family === group.value && device.manufacturer === manufacturer.value);
  options(screen, presets.map(({ id, name, width, height }) =>
    [id, name + ' · ' + width + ' × ' + height]), 'pixel_11');
  updatePreview();
}
function updatePreview() {
  for (const card of patternGrid.children) {
    card.setAttribute('aria-pressed', String(card.value === pattern.value));
  }
  const active = theme();
  const device = devices(active).find(item => item.id === screen.value);
  const matches = active.files.filter(([file, design, name, family, , , variant]) =>
    design === pattern.value && name === device?.preset && family === group.value &&
    variant === style.value && manufacturerFor(file) === manufacturer.value);
  const withCamera = device?.shared ? undefined : matches.find(row => row[7]);
  byId('camera-label').hidden = !withCamera;
  const row = (camera.checked && withCamera) || matches.find(item => !item[7]);
  if (!row) {
    selected = null;
    preview.hidden = true;
    svg.hidden = true;
    png.disabled = true;
    return;
  }
  const [file, design, , , width, height, , accuracy, svgOnly] = row;
  const src = '/art/' + active.id + '/' + file;
  const assetName = file.split('/').pop();
  selected = {
    src,
    pngUrl: active.pngRoot && !svgOnly ? active.pngRoot + file.replace(/\.svg$/, '.png') : null,
    file: device.shared ? device.id + assetName.slice(device.preset.length) : assetName,
    width,
    height
  };
  preview.hidden = false;
  preview.src = src;
  preview.alt = active.designs[design] + ' wallpaper for ' + device.name;
  svg.href = src;
  svg.download = selected.file;
  svg.hidden = false;
  png.disabled = false;
  byId('selection-name').textContent = active.designs[design];
  byId('dimensions').textContent = width + ' × ' + height + ' px';
  byId('detail').textContent = accuracy === 'estimated'
    ? 'Estimated camera placement. Fit may vary by device and wallpaper zoom.'
    : '';
  status.textContent = '';
}
appearance.value = document.documentElement.dataset.theme || 'system';
appearance.addEventListener('change', () => {
  if (appearance.value === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = appearance.value;
  try { localStorage.setItem('klob-wallpaper-theme', appearance.value); } catch {}
});
collection.addEventListener('change', updateCollections);
group.addEventListener('change', updateManufacturers);
manufacturer.addEventListener('change', updateScreens);
for (const control of [pattern, screen, style, camera]) {
  control.addEventListener('change', updatePreview);
}
async function rasterize(item) {
  const image = new Image();
  image.src = item.src;
  await image.decode();
  const canvas = document.createElement('canvas');
  canvas.width = item.width;
  canvas.height = item.height;
  canvas.getContext('2d').drawImage(image, 0, 0, item.width, item.height);
  return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
}
png.addEventListener('click', async () => {
  if (!selected) return;
  const item = selected;
  png.disabled = true;
  status.textContent = 'Preparing PNG…';
  try {
    let blob = null;
    if (item.pngUrl) {
      try {
        const response = await fetch(item.pngUrl, {signal: AbortSignal.timeout(8000)});
        if (response.ok && response.headers.get('content-type')?.includes('image/png')) {
          blob = await response.blob();
        }
      } catch {}
    }
    if (!blob) blob = await rasterize(item);
    if (!blob) throw new Error('PNG export failed');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = item.file.replace(/\.svg$/, '.png');
    link.click();
    const url = link.href;
    setTimeout(() => URL.revokeObjectURL(url), 30000);
    status.textContent = 'PNG ready.';
  } catch {
    status.textContent = 'PNG export failed. Try again or download the SVG.';
  } finally {
    png.disabled = !selected;
  }
});
try {
  const response = await fetch('/catalog.json');
  if (!response.ok) throw new Error('Catalog unavailable');
  ({themes} = await response.json());
  options(collection, themes.map(item => [item.id, item.label]));
  updateCollections();
} catch {
  byId('selection-name').textContent = 'Wallpapers Unavailable';
  status.textContent = 'Reload to try again.';
}
