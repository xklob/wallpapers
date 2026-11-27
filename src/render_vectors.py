#!/usr/bin/env python3
"""Reconstruct the geometric wallpaper collection as genuine, portable SVGs.

Usage: python render_vectors.py --data-root /path/to/project --output /path/to/svg
Inputs: config/cells.json (or data/cells.json/work/cells.json), a manifest directory supplied
with --png-root, original source images, and frame masters referenced by cells.
Raster sources are sampled only to fit palettes; SVG output never embeds them.
"""
from __future__ import annotations

import argparse
import colorsys
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image
from pattern_edges import align_vertical_edges
from connected_honeycomb import connect

STROKES = {"01_honeycomb_bright": 2.0, "02_hexagons": 2.4,
           "04_cubes": 2.2}


def num(value):
    return f"{float(value):.3f}".rstrip("0").rstrip(".")


def resolve_source(root, name):
    p = Path(name)
    candidates = [p] if p.is_absolute() else [root / p]
    candidates += [root / "sources" / p.name, root / "sources/input" / p.name,
                   root / "sources/frame_masters" / p.name,
                   root / "upload" / p.name, root / "generated_images" / p.name]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"Cannot resolve source {name!r} below {root}")


def rgbhex(rgb):
    return "#" + "".join(f"{max(0,min(255,round(float(c)*255))):02x}" for c in rgb)


def fit_palette(path, cube=False):
    a = np.asarray(Image.open(path).convert("RGB"), dtype=float) / 255
    hi = a.max(2); lo = a.min(2); delta = hi - lo
    keep = (hi > .55) & (delta > .5)
    yy, xx = np.where(keep)
    # Evenly sample the complete artwork, rather than favoring a dense corner.
    step = max(1, len(xx)//12000)
    xx, yy = xx[::step], yy[::step]
    sample = a[yy, xx]; maximum = sample.max(1); minimum = sample.min(1)
    diff = maximum - minimum
    h = np.zeros(len(sample))
    channel = sample.argmax(1)
    m = channel == 0; h[m] = ((sample[m,1]-sample[m,2])/diff[m]) % 6
    m = channel == 1; h[m] = (sample[m,2]-sample[m,0])/diff[m]+2
    m = channel == 2; h[m] = (sample[m,0]-sample[m,1])/diff[m]+4
    h /= 6
    matrix = np.column_stack([xx, yy, np.ones(len(xx))])
    initial = .00054 if cube else .00044
    params = np.array([initial, initial, -.06])
    for _ in range(5):
        predicted = matrix @ params
        unwrapped = h + np.rint(predicted-h)
        params = np.linalg.lstsq(matrix, unwrapped, rcond=None)[0]
    # Fit peak stroke brightness, excluding antialiased low-value edge pixels.
    value = float(np.quantile(maximum, .9))
    saturation = float(np.quantile(diff/maximum, .75))
    return [float(x) for x in params], saturation, value


def hue_gradient(palette):
    (ax, ay, offset), saturation, value = palette
    span = max(.1, 1254*(ax+ay))
    denom = ax*ax+ay*ay
    x2 = ax*span/denom; y2 = ay*span/denom
    positions = [0., 1.]
    for k in range(math.floor(offset*6)-1, math.ceil((offset+span)*6)+2):
        t = (k/6-offset)/span
        if 0 < t < 1:
            positions.append(t)
    stops = "".join(f'<stop offset="{num(t)}" stop-color="{rgbhex(colorsys.hsv_to_rgb((offset+span*t)%1,saturation,value))}"/>'
                    for t in sorted(positions))
    return f'<linearGradient id="rainbow" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="{num(x2)}" y2="{num(y2)}">{stops}</linearGradient>'


def grid_polygons(design):
    cells = design["cells"]; rows = []
    for cell in sorted(cells, key=lambda c:c["y"]+(c["h"]-1)/2):
        cy = cell["y"]+(cell["h"]-1)/2
        if not rows or abs(cy-np.mean([c["y"]+(c["h"]-1)/2 for c in rows[-1]])) > 10:
            rows.append([])
        rows[-1].append(cell)
    centers_y = [np.mean([c["y"]+(c["h"]-1)/2 for c in row]) for row in rows]
    dy, yoff = np.polyfit(np.arange(1,len(rows)+1), centers_y, 1)
    dx = float(np.median([b-a for row in rows for a,b in zip(
        sorted(c["x"]+(c["w"]-1)/2 for c in row),
        sorted(c["x"]+(c["w"]-1)/2 for c in row)[1:])]))
    residual = []
    for k,row in enumerate(rows,1):
        phase = dx/2 if k%2 else 0
        residual += [((c["x"]+(c["w"]-1)/2-phase+dx/2)%dx)-dx/2 for c in row]
    xoff = float(np.median(residual))
    ww = float(np.median([c["w"]-1 for c in cells])); hh = float(np.median([c["h"]-1 for c in cells]))
    polygons = []
    for row in range(-1,17):
        for col in range(-1,16):
            cx = xoff+col*dx+(dx/2 if row%2 else 0); cy = yoff+row*dy
            if cx+ww/2<0 or cx-ww/2>1254 or cy+hh/2<0 or cy-hh/2>1254:
                continue
            nearest = min(cells,key=lambda c:(c["x"]+(c["w"]-1)/2-cx)**2+(c["y"]+(c["h"]-1)/2-cy)**2)
            delta = (nearest["x"]+(nearest["w"]-1)/2-cx)**2+(nearest["y"]+(nearest["h"]-1)/2-cy)**2
            if delta<25:
                x,y=nearest["x"],nearest["y"];w,h=nearest["w"]-1,nearest["h"]-1
            else:
                x,y,w,h=cx-ww/2,cy-hh/2,ww,hh
            polygons.append([(x+w/2,y),(x+w,y+h/4),(x+w,y+3*h/4),
                             (x+w/2,y+h),(x,y+3*h/4),(x,y+h/4)])
    return polygons


def frame_colors(path):
    a = np.asarray(Image.open(path).convert("RGB"))
    v = a.max(2)
    top = int(np.argmax(v[:50,50:-50].mean(1)))
    bottom = 1204+int(np.argmax(v[-50:,50:-50].mean(1)))
    left = int(np.argmax(v[50:-50,:50].mean(0)))
    right = 1204+int(np.argmax(v[50:-50,-50:].mean(0)))
    lines = [("top",lambda t:(round(25+1203*t),top)),
             ("right",lambda t:(right,round(25+1203*t))),
             ("bottom",lambda t:(round(25+1203*t),bottom)),
             ("left",lambda t:(left,round(25+1203*t)))]
    result = {}
    for name, position in lines:
        samples=[]
        for t in np.linspace(0,1,25):
            x,y=position(t)
            patch=a[max(0,y-2):min(1254,y+3),max(0,x-2):min(1254,x+3)]
            strength=patch.max(2)
            rgb=patch.reshape(-1,3)[int(strength.argmax())]/255
            samples.append((float(t),rgbhex(rgb)))
        result[name]=samples
    return result


def frame_svg(width,height,colors,radius=0):
    short=min(width,height); margin=.02*short; stroke=.006*short
    x0=y0=margin;x1=width-margin;y1=height-margin
    edges=[("top",x0,y0,x1,y0),("right",x1,y0,x1,y1),
           ("bottom",x0,y1,x1,y1),("left",x0,y0,x0,y1)]
    defs=[]; paths=[]
    for name,ax,ay,bx,by in edges:
        stops="".join(f'<stop offset="{num(t)}" stop-color="{c}"/>' for t,c in colors[name])
        defs.append(f'<linearGradient id="frame-{name}" gradientUnits="userSpaceOnUse" x1="{num(ax)}" y1="{num(ay)}" x2="{num(bx)}" y2="{num(by)}">{stops}</linearGradient>')
        if radius:
            if name in ("bottom", "left"):
                ax,ay,bx,by = bx,by,ax,ay
            horizontal = (bx-ax) / max(1, abs(bx-ax))
            vertical = (by-ay) / max(1, abs(by-ay))
            inward_x, inward_y = -vertical, horizontal
            start = ax+horizontal*radius, ay+vertical*radius
            stop = bx-horizontal*radius, by-vertical*radius
            diagonal = radius / math.sqrt(2)
            lead = (start[0]-horizontal*diagonal+inward_x*(radius-diagonal),
                    start[1]-vertical*diagonal+inward_y*(radius-diagonal))
            tail = (stop[0]+horizontal*diagonal+inward_x*(radius-diagonal),
                    stop[1]+vertical*diagonal+inward_y*(radius-diagonal))
            arc = f'A{num(radius)},{num(radius)} 0 0 1 '
            position = lambda point: ",".join(num(c) for c in point)
            path = f'M{position(lead)}{arc}{position(start)}L{position(stop)}{arc}{position(tail)}'
        else:
            path = f'M{num(ax)},{num(ay)}L{num(bx)},{num(by)}'
        cap = "butt" if radius else "square"
        paths.append(f'<path d="{path}" fill="none" stroke="url(#frame-{name})" stroke-width="{num(stroke)}" stroke-linecap="{cap}"/>')
    return "".join(defs),"".join(paths)


def camera_svg(camera):
    if not camera:
        return "",""
    cx,cy=camera["camera_center"];outer=camera["ring_outer_radius"]
    stroke=outer*34/508; r=outer-stroke/2
    defs=[];parts=[f'<circle cx="{num(cx)}" cy="{num(cy)}" r="{num(outer+1.5)}" fill="#000"/>']
    for k in range(6):
        ta=-math.pi/2+k*math.pi/3;tb=ta+math.pi/3
        ax,ay=cx+r*math.cos(ta),cy+r*math.sin(ta)
        bx,by=cx+r*math.cos(tb),cy+r*math.sin(tb)
        ca=rgbhex(colorsys.hsv_to_rgb(k/6,1,1));cb=rgbhex(colorsys.hsv_to_rgb(((k+1)/6)%1,1,1))
        defs.append(f'<linearGradient id="camera-{k}" gradientUnits="userSpaceOnUse" x1="{num(ax)}" y1="{num(ay)}" x2="{num(bx)}" y2="{num(by)}"><stop stop-color="{ca}"/><stop offset="1" stop-color="{cb}"/></linearGradient>')
        parts.append(f'<path d="M{num(ax)},{num(ay)}A{num(r)},{num(r)} 0 0 1 {num(bx)},{num(by)}" fill="none" stroke="url(#camera-{k})" stroke-width="{num(stroke)}"/>')
    return "".join(defs),"".join(parts)


def build_svg(meta,base,design,polygons,palette,frame,camera=None):
    width,height=meta["width"],meta["height"]
    x,y,bw,bh=base["source_box"];tw,th=base["pattern_size"]
    pad=base["pad"];iw,ih=width-2*pad,height-2*pad
    px=pad+(iw-tw)//2;py=pad+(ih-th)//2
    sx,sy=tw/bw,th/bh
    paths=[];spokes=[];faint=[]
    for poly in polygons:
        if max(p[0] for p in poly)<x or min(p[0] for p in poly)>x+bw or max(p[1] for p in poly)<y or min(p[1] for p in poly)>y+bh:
            continue
        paths.append("M"+"L".join(f"{num(a)},{num(b)}" for a,b in poly)+"Z")
        if design["id"]=="04_cubes":
            cx=(poly[0][0]+poly[3][0])/2;cy=(poly[0][1]+poly[3][1])/2
            for i in (1,3,5):
                a,b=poly[i];spokes.append(f'M{num(cx)},{num(cy)}L{num(a)},{num(b)}')
            for i in (0,2,4):
                a,b=poly[i];faint.append(f'M{num(cx)},{num(cy)}L{num(a)},{num(b)}')
    stroke=STROKES[design["id"]]
    group=[f'<path d="{"".join(paths)}" fill="none" stroke="url(#rainbow)" stroke-width="{num(stroke)}" stroke-linejoin="round"/>']
    if faint:
        group.append(f'<path d="{"".join(faint)}" fill="none" stroke="url(#rainbow)" stroke-width="1.2" opacity="0.18"/>')
        group.append(f'<path d="{"".join(spokes)}" fill="none" stroke="url(#rainbow)" stroke-width="{num(stroke)}"/>')
    caps=[]
    for cap in base["caps"]:
        a,b=cap["a"],cap["b"]
        ax,ay=x+a[0]/sx,y+a[1]/sy;bx,by=x+b[0]/sx,y+b[1]/sy
        caps.append(f'M{num(ax)},{num(ay)}L{num(bx)},{num(by)}')
    group.append(f'<path d="{"".join(caps)}" fill="none" stroke="url(#rainbow)" stroke-width="{num(base["cap_stroke"]/min(sx,sy))}" stroke-linecap="round"/>')
    radius=meta.get("frame_radius",0)
    frame_defs,frame_paths=frame_svg(width,height,frame,radius) if meta["style"]=="rgb_border" else ("","")
    camera_defs,camera_paths=camera_svg(camera)
    defs=hue_gradient(palette)+f'<clipPath id="pattern-clip"><rect x="{num(x)}" y="{num(y)}" width="{num(bw)}" height="{num(bh)}"/></clipPath>'+frame_defs+camera_defs
    pattern=f'<g transform="translate({num(px)} {num(py)}) scale({num(sx)} {num(sy)}) translate({num(-x)} {num(-y)})" clip-path="url(#pattern-clip)">'+"".join(group)+'</g>'
    title=design["id"].replace("_"," ")+" | "+str(width)+"x"+str(height)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}"><title>{title}</title><defs>{defs}</defs><rect width="{width}" height="{height}" fill="#000"/>{frame_paths}{pattern}{camera_paths}</svg>\n'
    svg = align_vertical_edges(svg)
    if radius:
        corner = max(0,radius-(pad-.02*min(width,height)))
        rounded = f' rx="{num(corner/sx)}" ry="{num(corner/sy)}"'
        svg = re.sub(r'(<clipPath id="pattern-clip"[^>]*><rect[^>]*?)/>', r'\1'+rounded+'/>',svg)
    return connect(svg) if design["id"] == "01_honeycomb_bright" else svg


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root",type=Path,required=True)
    parser.add_argument("--png-root",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args();root=args.data_root.resolve();out=args.output.resolve()
    if args.png_root:
        png=args.png_root.resolve()
    elif (root/"config/base_files.json").exists():
        png=root/"config"
    else:
        png=root/"output/solid_cap_wallpapers"
    cell_path=next(p for p in [root/"config/cells.json",root/"data/cells.json",root/"work/cells.json",root/"cells.json"] if p.exists())
    designs=json.loads(cell_path.read_text());by_id={d["id"]:d for d in designs}
    base_manifest=png/"base_files.json" if (png/"base_files.json").exists() else png/"files.json"
    bases=json.loads(base_manifest.read_text());camera=json.loads((png/"camera_files.json").read_text())
    base_index={f["file"]:f for f in bases}
    palettes={d["id"]:fit_palette(resolve_source(root,d["source"]),d["id"]=="04_cubes") for d in designs}
    frames={d["id"]:frame_colors(resolve_source(root,d["master"])) for d in designs}
    polygons={d["id"]:grid_polygons(d) for d in designs}
    out.mkdir(parents=True,exist_ok=True);manifest=[]
    for meta in bases+camera:
        is_camera=meta.get("camera_outline",False)
        if is_camera:
            p=Path(meta["file"])
            stem=f'{meta["camera_profile"]}_{meta["width"]}x{meta["height"]}_{meta["style"]}.png'
            base=base_index[str(p.parent.parent/stem)]
        else:base=meta
        d=by_id[meta["design"]]
        target=out/Path(meta["file"]).with_suffix(".svg");target.parent.mkdir(parents=True,exist_ok=True)
        svg=build_svg(meta,base,d,polygons[d["id"]],palettes[d["id"]],frames[d["id"]],meta if is_camera else None)
        parsed=ET.fromstring(svg)
        assert parsed.attrib["width"]==str(meta["width"]) and parsed.attrib["height"]==str(meta["height"])
        assert not any(el.tag.split("}")[-1]=="image" for el in parsed.iter())
        assert "data:" not in svg
        target.write_text(svg)
        manifest.append({**meta,"file":str(target.relative_to(out)),"format":"svg","bytes":target.stat().st_size})
    (out/"files.json").write_text(json.dumps(manifest,indent=2)+"\n")
    (out/"palette_fits.json").write_text(json.dumps({k:{"hue_plane":v[0],"saturation":v[1],"brightness":v[2]} for k,v in palettes.items()},indent=2)+"\n")
    total=sum(m["bytes"] for m in manifest)
    (out/"README.md").write_text(f'''# Vector wallpaper collection

{len(manifest)} genuine SVG wallpapers, totaling {total:,} bytes ({total/1e6:.2f} MB).
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
''')
    raster_root=png if (png/bases[0]["file"]).is_file() else root/"renders/png"
    png_total=sum((raster_root/m["file"]).stat().st_size for m in bases+camera if (raster_root/m["file"]).is_file())
    samples=[]
    for design in designs:
        item=next(m for m in manifest if m["design"]==design["id"] and m["width"]==3840 and m["height"]==2160 and m["style"]=="rgb_border" and not m.get("camera_outline"))
        raster=raster_root/Path(item["file"]).with_suffix(".png")
        raster_bytes=raster.stat().st_size if raster.is_file() else None
        samples.append({"design":design["id"],"width":3840,"height":2160,"svg_bytes":item["bytes"],"png_bytes":raster_bytes,
                        "reduction_percent":round(100*(1-item["bytes"]/raster_bytes),3) if raster_bytes else None})
    report={"svg_count":len(manifest),"svg_artwork_bytes":total,"png_artwork_bytes":png_total or None,
            "reduction_percent":round(100*(1-total/png_total),3) if png_total else None,"samples":samples,
            "validation":{"parsed_xml":len(manifest),"embedded_raster_images":0,"external_asset_dependencies":0,"exact_dimensions":True}}
    (out/"comparison.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({"svg_count":len(manifest),"svg_bytes":total,"min_bytes":min(m["bytes"] for m in manifest),"max_bytes":max(m["bytes"] for m in manifest),"output":str(out)}))


if __name__=="__main__":main()
