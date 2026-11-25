import json, shutil, subprocess, os
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'renders/png'
RING=ROOT/'assets/rgb_camera_ring.png'

PROFILES = json.loads((ROOT / "config/camera_profiles.json").read_text())

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
 designs=['01_honeycomb_bright','02_hexagons','04_cubes']
 jobs=[(d,p,s) for d in designs for p in PROFILES for s in ['original','rgb_border']]
 with ThreadPoolExecutor(max_workers=4) as pool:files=list(pool.map(export,jobs))
 (OUT/'camera_files.json').write_text(json.dumps(files,indent=2)+'\n')
 tools=OUT/'camera_tools';tools.mkdir(exist_ok=True)
 shutil.copyfile(RING,tools/'rgb_camera_ring.png')
 (tools/'camera_profiles.json').write_text(json.dumps(PROFILES,indent=2)+'\n')
 print(f"Rendered {len(files)} camera-outline wallpapers", flush=True)

if __name__ == '__main__':
 main()
