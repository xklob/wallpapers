import json, re, subprocess
from pathlib import Path
from PIL import Image
import numpy as np
from scipy import ndimage

ROOT=Path(__file__).resolve().parents[1]
designs=json.loads((ROOT/'config/masters.json').read_text())
for d in designs:
    cmd=['magick',d['source'],'-background','black','-alpha','remove','-alpha','off',
         '-separate','-evaluate-sequence','max','-threshold','18%',
         '-define','connected-components:verbose=true',
         '-define','connected-components:mean-color=true',
         '-connected-components','8','null:']
    result=subprocess.run(cmd,check=True,text=True,capture_output=True)
    pixels=np.asarray(Image.open(d['source']).convert('RGB'))
    binary=pixels.max(axis=2)>255*.18
    cells=[]
    for line in (result.stdout+result.stderr).splitlines():
        m=re.match(r'\s+(\d+): (\d+)x(\d+)\+(\d+)\+(\d+) .*? ([\d.]+) gray\(255\)',line)
        if not m: continue
        cid,w,h,x,y=map(int,m.groups()[:5])
        if not (101<=w<=114 and 115<=h<=132 and x>1 and y>1 and x+w<1253 and y+h<1253): continue
        patch=binary[y:y+h,x:x+w]
        filled=ndimage.binary_fill_holes(patch)
        # A complete outer contour encloses a large polygon interior.
        holes=int((filled & ~patch).sum())
        if holes<6500: continue
        cells.append({'id':cid,'x':x,'y':y,'w':w,'h':h,'holes':holes})
    d['cells']=cells
    print(d['id'],len(cells),'complete cells',[(c['x'],c['y']) for c in cells[:3]])
    assert len(cells)>40,d['id']
(ROOT/'config/cells.json').write_text(json.dumps(designs,indent=2))
