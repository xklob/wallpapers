const byId = id => document.getElementById(id);
const collection = byId('collection');
const pattern = byId('pattern');
const patternGrid = byId('pattern-grid');
const patternViewer = byId('pattern-viewer');
const viewerVideo = byId('pattern-viewer-video');
const group = byId('device-group');
const manufacturer = byId('manufacturer');
const screen = byId('screen');
const style = byId('style');
const camera = byId('camera');
const homeScreen = byId('home-screen');
const statusBar = byId('status-bar');
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
  for (const [file, , id, family, width, height, , cameraRing, , homeOutline, statusOutline] of active.files) {
    if (cameraRing || homeOutline || statusOutline) continue;
    const manufacturer = manufacturerFor(file);
    presets.set(id, { id, preset: id, family, manufacturer, width, height, name: label(id) });
  }
  const hidden = new Set(active.hiddenPresets);
  const aliases = (active.deviceAliases ?? []).map(alias => ({
    ...presets.get(alias.preset), ...alias
  }));
  return [...aliases, ...[...presets.values()].filter(device => !hidden.has(device.id))];
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
function patternCard(name, poster, onOpen) {
  const card = document.createElement('button');
  card.type = 'button';
  card.className = 'pattern-card';
  card.setAttribute('aria-haspopup', 'dialog');
  const image = document.createElement('img');
  image.src = poster;
  image.alt = '';
  image.decoding = 'async';
  const caption = document.createElement('span');
  caption.textContent = name;
  card.append(image, caption);
  card.addEventListener('click', onOpen);
  return card;
}
function openPatternViewer(name, live) {
  byId('pattern-viewer-title').textContent = name;
  byId('pattern-viewer-still').hidden = live;
  byId('use-pattern').hidden = live;
  viewerVideo.hidden = !live;
  byId('live-app').hidden = !live;
  patternViewer.showModal();
}
function updatePatternGallery(active) {
  const cards = [];
  for (const [design, name] of Object.entries(active.designs)) {
    const originals = active.files.filter(row =>
      row[1] === design && row[6] === 'original' && !row[7]);
    const sample = originals.find(row => row[3] === 'Desktop' && row[4] === 1920 && row[5] === 1080)
      ?? originals.find(row => row[3] === 'Desktop') ?? originals[0];
    if (!sample) continue;
    const portrait = originals.find(row => row[2] === 'pixel_9_pro_xl')
      ?? originals.find(row => row[3] === 'Phones' && row[5] > row[4]);

    const poster = '/art/' + active.id + '/' + sample[0];
    cards.push(patternCard(name, poster, () => {
      const large = byId('pattern-viewer-image');
      const portraitSource = byId('pattern-viewer-portrait');
      if (portrait) portraitSource.srcset = '/art/' + active.id + '/' + portrait[0];
      else portraitSource.removeAttribute('srcset');
      large.src = poster;
      large.alt = name + ' wallpaper';
      byId('use-pattern').value = design;
      openPatternViewer(name, false);
    }));
  }
  for (const {name, poster, video} of active.livePreviews ?? []) {
    cards.push(patternCard(name + ' · Android live', poster, () => {
      viewerVideo.poster = poster;
      viewerVideo.src = video;
      openPatternViewer(name, true);
      if (!matchMedia('(prefers-reduced-motion: reduce)').matches) {
        viewerVideo.play().catch(() => {});
      }
    }));
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
  const active = theme();
  const device = devices(active).find(item => item.id === screen.value);
  const cameraPreset = device?.cameraPreset ?? (device?.id === device?.preset ? device?.id : null);
  const matches = active.files.filter(([, design, id, , , , variant, cameraRing, , homeOutline, statusOutline]) => {
    if (design !== pattern.value || variant !== style.value) return false;
    if (homeOutline || statusOutline) return id === device?.id;
    return id === (cameraRing ? cameraPreset : device?.preset);
  });
  const canAddCamera = matches.some(row => row[7]);
  const canAddHome = matches.some(row => row[9]);
  const canAddStatus = matches.some(row => row[10]);
  byId('camera-label').hidden = !canAddCamera;
  byId('home-label').hidden = !canAddHome;
  byId('status-bar-label').hidden = !canAddStatus;
  const row = matches.find(item => Boolean(item[7]) === (canAddCamera && camera.checked) &&
    Boolean(item[9]) === (canAddHome && homeScreen.checked) &&
    Boolean(item[10]) === (canAddStatus && statusBar.checked));
  if (!row) {
    selected = null;
    preview.hidden = true;
    svg.hidden = true;
    png.disabled = true;
    return;
  }
  const [file, design, assetPreset, , width, height, , accuracy, svgOnly, homeOutline, statusOutline] = row;
  const src = '/art/' + active.id + '/' + file;
  const assetName = file.split('/').pop();
  selected = {
    src,
    pngUrl: active.pngRoot && !svgOnly ? active.pngRoot + file.replace(/\.svg$/, '.png') : null,
    file: device.id + assetName.slice(assetPreset.length),
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
  const detail = [];
  if (accuracy === 'estimated') detail.push('Estimated camera placement. Fit can vary with wallpaper zoom.');
  if (homeOutline && device.id === 'pixel_tablet_portrait') {
    detail.push('Estimated stock Pixel Tablet portrait layout.');
  } else if (homeOutline || statusOutline) {
    detail.push('Fits the stock ' + device.name + ' layout.');
  }
  byId('detail').textContent = detail.join(' ');
  status.textContent = '';
}
appearance.value = document.documentElement.dataset.theme || 'system';
appearance.addEventListener('change', () => {
  if (appearance.value === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = appearance.value;
  try { localStorage.setItem('klob-wallpaper-theme', appearance.value); } catch {}
});
collection.addEventListener('change', updateCollections);
patternViewer.addEventListener('click', event => {
  if (event.target === patternViewer) patternViewer.close();
});
patternViewer.addEventListener('close', () => viewerVideo.pause());
byId('live-app').addEventListener('click', () => {
  patternViewer.close();
  byId('download-android').focus();
});
byId('use-pattern').addEventListener('click', event => {
  pattern.value = event.currentTarget.value;
  updatePreview();
  patternViewer.close();
  pattern.focus();
});
group.addEventListener('change', updateManufacturers);
manufacturer.addEventListener('change', updateScreens);
for (const control of [pattern, screen, style, camera, homeScreen, statusBar]) {
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
