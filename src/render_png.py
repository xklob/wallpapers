import json, math, os, subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from PIL import Image
import numpy as np
from device_presets import PRESETS
from export_aligned import select_cells

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'renders/png'
WORK=ROOT/'build/solid_caps'
WORK.mkdir(parents=True,exist_ok=True)
DESIGNS=json.loads((ROOT/'config/cells.json').read_text())
PIXELS={d['id']:np.asarray(Image.open(d['source']).convert('RGB')) for d in DESIGNS}

def run(args):
    subprocess.run(['magick',*map(str,args)],check=True,env=dict(os.environ,MAGICK_THREAD_LIMIT='1'),stdout=subprocess.DEVNULL)

def grid_polygons(d):
    cells=d['cells']; rows=[]
    for c in sorted(cells,key=lambda c:c['y']+(c['h']-1)/2):
        cy=c['y']+(c['h']-1)/2
        if not rows or abs(cy-np.mean([z['y']+(z['h']-1)/2 for z in rows[-1]]))>10:rows.append([])
        rows[-1].append(c)
    row_y=[np.mean([c['y']+(c['h']-1)/2 for c in row]) for row in rows]
    dy,yoff=np.polyfit(np.arange(1,len(rows)+1),row_y,1)
    dx=float(np.median([b-a for row in rows for a,b in zip(sorted(c['x']+(c['w']-1)/2 for c in row),sorted(c['x']+(c['w']-1)/2 for c in row)[1:])]))
    residual=[]
    for k,row in enumerate(rows,1):
        phase=dx/2 if k%2 else 0
        residual += [((c['x']+(c['w']-1)/2-phase+dx/2)%dx)-dx/2 for c in row]
    xoff=float(np.median(residual))
    ww=float(np.median([c['w']-1 for c in cells]));hh=float(np.median([c['h']-1 for c in cells]))
    result=[]
    for row in range(-1,17):
        for col in range(-1,16):
            cx=xoff+col*dx+(dx/2 if row%2 else 0);cy=yoff+row*dy
            if cx+ww/2<0 or cx-ww/2>1254 or cy+hh/2<0 or cy-hh/2>1254:continue
            nearest=min(cells,key=lambda c:(c['x']+(c['w']-1)/2-cx)**2+(c['y']+(c['h']-1)/2-cy)**2)
            delta=(nearest['x']+(nearest['w']-1)/2-cx)**2+(nearest['y']+(nearest['h']-1)/2-cy)**2
            if delta<25:
                x,y=nearest['x'],nearest['y'];w,h=nearest['w']-1,nearest['h']-1
            else:x,y,w,h=cx-ww/2,cy-hh/2,ww,hh
            polygon=[(x+w/2,y),(x+w,y+h/4),(x+w,y+3*h/4),(x+w/2,y+h),(x,y+3*h/4),(x,y+h/4)]
            result.append(polygon)
    return result

POLYGONS={d['id']:grid_polygons(d) for d in DESIGNS}

def cap_segments(poly,rect):
    x0,y0,x1,y1=rect
    xmin=min(x for x,y in poly);xmax=max(x for x,y in poly)
    ymin=min(y for x,y in poly);ymax=max(y for x,y in poly)
    if xmax<=x0 or xmin>=x1 or ymax<=y0 or ymin>=y1:return []
    caps=[]
    for axis,value,lo,hi,side in [(0,x0,y0,y1,'left'),(0,x1,y0,y1,'right'),(1,y0,x0,x1,'top'),(1,y1,x0,x1,'bottom')]:
        low=xmin if axis==0 else ymin;high=xmax if axis==0 else ymax
        if not low+.2<value<high-.2:continue
        hits=[]
        for a,b in zip(poly,poly[1:]+poly[:1]):
            den=b[axis]-a[axis]
            if abs(den)<1e-9:continue
            t=(value-a[axis])/den
            if -1e-8<=t<=1+1e-8:hits.append(a[1-axis]+t*(b[1-axis]-a[1-axis]))
        if len(hits)<2:continue
        start=max(lo,min(hits));end=min(hi,max(hits))
        if end-start<1:continue
        a=(value,start) if axis==0 else (start,value)
        b=(value,end) if axis==0 else (end,value)
        caps.append((a,b,side))
    return caps

def color_at(d,p):
    a=PIXELS[d['id']];px,py=p
    for radius in [8,24,80]:
        x0=max(0,int(px)-radius);x1=min(1254,int(px)+radius+1)
        y0=max(0,int(py)-radius);y1=min(1254,int(py)+radius+1)
        region=a[y0:y1,x0:x1]
        strength=region.max(axis=2).astype(float)
        yy,xx=np.mgrid[y0:y1,x0:x1]
        scores=(xx-px)**2+(yy-py)**2+3*(1-strength/255)
        scores[strength<70]=np.inf
        if np.isfinite(scores).any():
            ry,rx=np.unravel_index(np.argmin(scores),scores.shape)
            return '#'+''.join(f'{int(c):02x}' for c in region[ry,rx])
    return '#00ffff'

def content(d,iw,ih,key):
    whole,box=select_cells(d,iw,ih)
    x,y,bw,bh=box
    scale=min(iw/bw,ih/bh);tw=round(bw*scale);th=round(bh*scale)
    sx,sy=tw/bw,th/bh
    stroke_source={'01_honeycomb_bright':2.0,'02_hexagons':2.4,'04_cubes':2.2}[d['id']]
    stroke=max(1.5,stroke_source*scale)
    rect=(x,y,x+bw-1,y+bh-1)
    caps=[c for poly in POLYGONS[d['id']] for c in cap_segments(poly,rect)]
    defs=[];lines=[];records=[];mvg=[]
    inset=stroke/2+.5
    def target(p):
        return (max(inset,min(tw-1-inset,(p[0]-x)*sx)),max(inset,min(th-1-inset,(p[1]-y)*sy)))
    for i,(a,b,side) in enumerate(caps):
        ta,tb=target(a),target(b)
        ca,cb=color_at(d,a),color_at(d,b)
        defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{ta[0]}" y1="{ta[1]}" x2="{tb[0]}" y2="{tb[1]}"><stop offset="0" stop-color="{ca}"/><stop offset="1" stop-color="{cb}"/></linearGradient>')
        lines.append(f'<path d="M {ta[0]} {ta[1]} L {tb[0]} {tb[1]}" fill="none" stroke="url(#g{i})" stroke-width="{stroke}" stroke-linecap="round"/>')
        mvg.append(f"push gradient g{i} linear {ta[0]},{ta[1]} {tb[0]},{tb[1]} stop-color '{ca}' 0 stop-color '{cb}' 1 pop gradient stroke 'url(#g{i})' stroke-width {stroke} stroke-linecap round line {ta[0]},{ta[1]} {tb[0]},{tb[1]}")
        records.append({'a':list(ta),'b':list(tb),'side':side})
    svg=WORK/f'{key}_caps.svg'
    svg.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{tw}" height="{th}" viewBox="0 0 {tw} {th}"><defs>'+''.join(defs)+'</defs>'+''.join(lines)+'</svg>')
    pattern=WORK/f'{key}_content.png'
    run([d['source'],'-background','black','-alpha','remove','-alpha','off',
         '-crop',f'{bw}x{bh}+{x}+{y}','+repage','-filter','Lanczos','-resize',f'{tw}x{th}!',
         '(','-size',f'{tw}x{th}','xc:none','-draw',' '.join(mvg),')',
         '-compose','Over','-composite','-background','black','-gravity','center','-extent',f'{iw}x{ih}',
         '-strip',pattern])
    return pattern,box,records,(tw,th),stroke

def export(job):
    d,folder,name,w,h,style=job
    key=f"{d['id']}_{w}x{h}_{style}"
    short=min(w,h);pad=round(short*(.085 if style=='rgb_border' else .025))
    iw,ih=w-2*pad,h-2*pad
    pattern,box,caps,pattern_size,stroke=content(d,iw,ih,key)
    args=['-size',f'{w}x{h}','xc:black']
    if style=='rgb_border':
        a=round(short*.04)
        tiles=[(0,0,50,50,0,0,a,a),(1204,0,50,50,w-a,0,a,a),
               (0,1204,50,50,0,h-a,a,a),(1204,1204,50,50,w-a,h-a,a,a),
               (50,0,1154,50,a,0,w-2*a,a),(50,1204,1154,50,a,h-a,w-2*a,a),
               (0,50,50,1154,0,a,a,h-2*a),(1204,50,50,1154,w-a,a,a,h-2*a)]
        for q in tiles:
            sx,sy,sw,sh,x,y,tw,th=q
            args+=['(',d['master'],'-crop',f'{sw}x{sh}+{sx}+{sy}','+repage','-resize',f'{tw}x{th}!',')',
                   '-gravity','northwest','-geometry',f'+{x}+{y}','-compose','Over','-composite']
    args+=[pattern,'-gravity','center','-geometry','+0+0','-compose','Over','-composite']
    target=OUT/d['id']/folder/style/f'{name}_{w}x{h}_{style}.png'
    target.parent.mkdir(parents=True,exist_ok=True)
    args+=['-colorspace','sRGB','-depth','8','-strip','-define','png:color-type=2','-define','png:compression-level=7',target]
    run(args)
    print(str(target.relative_to(OUT)),len(caps),'solid closures',flush=True)
    return {'file':str(target.relative_to(OUT)),'width':w,'height':h,'style':style,'design':d['id'],
            'pad':pad,'source_box':box,'caps':caps,'pattern_size':pattern_size,'cap_stroke':stroke}

def main():
    jobs=[(d,f,n,w,h,s) for d in DESIGNS for f,n,w,h in PRESETS for s in ['original','rgb_border']]
    with ThreadPoolExecutor(max_workers=4) as p: files=list(p.map(export,jobs))
    (OUT/'files.json').write_text(json.dumps(files,indent=2)+'\n')
    print(f"Rendered {len(files)} wallpapers", flush=True)

if __name__ == '__main__':
    main()
