import render_vectors as vector


def lattice_geometry(polygons):
    rows = []
    centers = sorted(((poly[0][0], (poly[0][1]+poly[3][1])/2) for poly in polygons),
                     key=lambda point: point[1])
    for x,y in centers:
        if not rows or abs(y-rows[-1][0][1]) > 4:
            rows.append([])
        rows[-1].append((x,y))
    ordinates = [vector.np.median([y for _,y in row]) for row in rows]
    dx = vector.np.median([b-a for row in rows
                           for a,b in zip(sorted(x for x,_ in row), sorted(x for x,_ in row)[1:])])
    dy = vector.np.median(vector.np.diff(ordinates))
    width = vector.np.median([poly[1][0]-poly[5][0] for poly in polygons])
    height = vector.np.median([poly[3][1]-poly[0][1] for poly in polygons])
    return dx, dy, width, height

def smaller_lattice(geometry, scale, phase_x, phase_y):
    dx, dy, width, height = (value*scale for value in geometry)
    polygons = []
    for row in range(-14,15):
        for column in range(-13,14):
            cx = 627+(column+0.5*(row%2)+phase_x)*dx
            cy = 627+(row+phase_y)*dy
            if cx+width/2 < 0 or cx-width/2 > 1254 or cy+height/2 < 0 or cy-height/2 > 1254:
                continue
            polygons.append([(cx,cy-height/2),(cx+width/2,cy-height/4),(cx+width/2,cy+height/4),
                             (cx,cy+height/2),(cx-width/2,cy+height/4),(cx-width/2,cy-height/4)])
    return polygons

def count_in_crop(polygons, box):
    x,y,width,height=box
    return sum(x <= poly[0][0] <= x+width and y <= (poly[0][1]+poly[3][1])/2 <= y+height
               for poly in polygons)
