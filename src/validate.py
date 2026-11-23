import json, math
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from PIL import Image
import numpy as np
from scipy import ndimage

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'renders/png'
files=json.loads((OUT/'files.json').read_text())
assert len(files)==480

def validate(f):
 key=f"{f['design']}_{f['width']}x{f['height']}_{f['style']}"
 with Image.open(OUT/f['file']) as im:
  assert im.size==(f['width'],f['height']) and im.mode=='RGB',key
  im.verify()
 with Image.open(ROOT/'build/solid_caps'/f'{key}_content.png') as im:a=np.asarray(im)
 tw,th=f['pattern_size'];iw,ih=a.shape[1],a.shape[0]
 ox,oy=(iw-tw)//2,(ih-th)//2
 # Every closing segment must be a visible, continuous solid line.
 for cap in f['caps']:
  for t in np.linspace(.03,.97,12):
   x=round(ox+cap['a'][0]*(1-t)+cap['b'][0]*t)
   y=round(oy+cap['a'][1]*(1-t)+cap['b'][1]*t)
   assert a[max(0,y-1):y+2,max(0,x-1):x+2].max()>45,(key,'gap in cap',cap,t)
 mask=a.max(axis=2)>60
 labels,n=ndimage.label(mask,np.ones((3,3)))
 loops=[];loose=[]
 for cid,sl in enumerate(ndimage.find_objects(labels),1):
  part=labels[sl]==cid;area=int(part.sum())
  if area<15:continue
  holes=int((ndimage.binary_fill_holes(part)&~part).sum())
  if holes>3:loops.append(sl)
  else:loose.append(sl)
 suspicious=[]
 for sl in loose:
  contained=any(sl[0].start>=o[0].start and sl[0].stop<=o[0].stop and sl[1].start>=o[1].start and sl[1].stop<=o[1].stop for o in loops)
  thin=min(sl[0].stop-sl[0].start,sl[1].stop-sl[1].start)<=math.ceil(f['cap_stroke']*2+3)
  if not contained and not thin:suspicious.append([sl[0].start,sl[0].stop,sl[1].start,sl[1].stop])
 return {'file':f['file'],'caps':len(f['caps']),'closed_contours':len(loops),'review':suspicious}

with ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(validate,files))
review=[x for x in results if x['review']]
(ROOT/'build/solid_validation.json').write_text(json.dumps({'results':results,'review':review},indent=2))
print('Checked',len(results),'base files;',sum(x['caps'] for x in results),'solid closures;',len(review),'files requiring contour review.',flush=True)

camera=json.loads((OUT/'camera_files.json').read_text()) if (OUT/'camera_files.json').exists() else []
for f in camera:
 with Image.open(OUT/f['file']) as im:
  assert im.size==(f['width'],f['height']) and im.mode=='RGB',f['file']
  im.verify()
print('Checked',len(camera),'camera-outline files.',flush=True)
