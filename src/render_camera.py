import json, shutil, subprocess, os
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from device_presets import PRESETS

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'renders/png'
RING=ROOT/'assets/rgb_camera_ring.png'

# Pixel profiles use Google's published native-pixel cutout paths. Other
# manufacturers' profiles are deliberately labeled estimated and can be tuned
# with the included offline camera-ring adjuster.
EXACT={
 'pixel_9':(539.5,86.5,42.0,'tokay'),
 'pixel_9_pro':(640.0,102.0,49.0,'caiman'),
 'pixel_9_pro_xl':(672.0,99.5,47.0,'komodo'),
}
SLAB={
 'galaxy_s24':(540,60,21),
 'galaxy_fhd_20x9':(540,65,21),
 'galaxy_s24_plus_ultra':(720,78,26),
}
FOLD={
 'galaxy_z_fold7_cover':(1080,2520,540,64,22),
 'galaxy_z_fold7_inner':(1968,2184,1476,76,25),
 'pixel_10_pro_fold_cover':(1080,2364,540,82,37),
 'pixel_10_pro_fold_inner':(2076,2152,1970,108,38),
 'oneplus_open_cover':(1116,2484,558,72,25),
 'oneplus_open_inner':(2268,2440,2154,105,28),
 'oppo_find_n5_cover':(1140,2616,570,76,25),
 'oppo_find_n5_inner':(2248,2480,2140,105,28),
 'honor_magic_v5_cover':(1060,2376,530,68,23),
 'honor_magic_v5_inner':(2172,2352,2068,101,27),
 'galaxy_z_flip7_inner':(1080,2520,540,62,22),
 'motorola_razr_ultra_2025_inner':(1224,2992,612,75,25),
}

PROFILES=[]
for folder,name,w,h in PRESETS:
 if name in EXACT:
  cx,cy,r,codename=EXACT[name]
  PROFILES.append({'folder':folder,'name':name,'width':w,'height':h,'cx':cx,'cy':cy,'cutout_radius':r,'accuracy':'published','source':f'https://android.googlesource.com/device/google/caimito/+/refs/heads/main/{codename}/overlay/frameworks/base/core/res/res/values/config.xml'})
 elif name in SLAB:
  cx,cy,r=SLAB[name]
  PROFILES.append({'folder':folder,'name':name,'width':w,'height':h,'cx':cx,'cy':cy,'cutout_radius':r,'accuracy':'estimated'})
 elif name.endswith('_portrait') or name.endswith('_landscape'):
  stem=name.rsplit('_',1)[0]
  if stem not in FOLD:continue
  pw,ph,cx,cy,r=FOLD[stem]
  if name.endswith('_landscape'):cx,cy=cy,pw-1-cx
  PROFILES.append({'folder':folder,'name':name,'width':w,'height':h,'cx':cx,'cy':cy,'cutout_radius':r,'accuracy':'estimated'})

def export(job):
 d,p,style=job
 w,h=p['width'],p['height']
 base=OUT/d/p['folder']/style/f"{p['name']}_{w}x{h}_{style}.png"
 suffix='camera_outline' if p['accuracy']=='published' else 'camera_outline_estimated'
 target=base.parent/suffix/f'{base.stem}_{suffix}.png'
 target.parent.mkdir(parents=True,exist_ok=True)
 radius=p['cutout_radius']+8
 diameter=round(radius*2)
 x=round(p['cx']-diameter/2);y=round(p['cy']-diameter/2)
 # A tiny black clearance keeps the ring readable over the geometric pattern.
 draw=f"fill black stroke none circle {p['cx']},{p['cy']} {p['cx']+radius+1.5},{p['cy']}"
 args=['magick',str(base),'-draw',draw,'(',str(RING),'-resize',f'{diameter}x{diameter}!',')',
       '-gravity','northwest','-geometry',f'+{x}+{y}','-compose','Over','-composite',
       '-colorspace','sRGB','-depth','8','-strip','-define','png:color-type=2','-define','png:compression-level=7',str(target)]
 subprocess.run(args,check=True,env=dict(os.environ,MAGICK_THREAD_LIMIT='1'),stdout=subprocess.DEVNULL)
 print(str(target.relative_to(OUT)),flush=True)
 return {'file':str(target.relative_to(OUT)),'width':w,'height':h,'style':style,'design':d,'camera_outline':True,'camera_accuracy':p['accuracy'],'camera_profile':p['name'],'camera_center':[p['cx'],p['cy']],'ring_outer_radius':radius}

def main():
 designs=['01_honeycomb_bright','02_hexagons','03_honeycomb_subtle','04_cubes']
 jobs=[(d,p,s) for d in designs for p in PROFILES for s in ['original','rgb_border']]
 with ThreadPoolExecutor(max_workers=4) as pool:files=list(pool.map(export,jobs))
 (OUT/'camera_files.json').write_text(json.dumps(files,indent=2)+'\n')
 tools=OUT/'camera_tools';tools.mkdir(exist_ok=True)
 shutil.copyfile(RING,tools/'rgb_camera_ring.png')
 (tools/'camera_profiles.json').write_text(json.dumps(PROFILES,indent=2)+'\n')
 print(f"Rendered {len(files)} camera-outline wallpapers", flush=True)

if __name__ == '__main__':
 main()
