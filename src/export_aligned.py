def select_cells(design,iw,ih):
    cells=design['cells']
    if iw>=ih:
        cw,ch=1254,1254*ih/iw
        starts=[(1254-ch)/2]+[c['y']-4 for c in cells]+[c['y']+c['h']+4-ch for c in cells]
        windows=[(0,max(0,min(1254-ch,s))) for s in starts]
    else:
        ch,cw=1254,1254*iw/ih
        starts=[(1254-cw)/2]+[c['x']-4 for c in cells]+[c['x']+c['w']+4-cw for c in cells]
        windows=[(max(0,min(1254-cw,s)),0) for s in starts]
    best=None
    for x,y in windows:
        keep=[c for c in cells if c['x']>=x+3 and c['y']>=y+3 and c['x']+c['w']<=x+cw-3 and c['y']+c['h']<=y+ch-3]
        if not keep: continue
        score=(len(keep),-abs(x+cw/2-627)-abs(y+ch/2-627))
        if best is None or score>best[0]: best=(score,keep)
    assert best
    keep=best[1]
    x0=max(0,min(c['x'] for c in keep)-4);y0=max(0,min(c['y'] for c in keep)-4)
    x1=min(1254,max(c['x']+c['w'] for c in keep)+4);y1=min(1254,max(c['y']+c['h'] for c in keep)+4)
    return keep,(x0,y0,x1-x0,y1-y0)
