"""Compare visible solid-border footprints independently of the engine's edge resolver."""
import argparse,json,math
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber
import numpy as np
from PIL import Image,ImageDraw,ImageChops,ImageFilter
from audit_cell_alignment import audit as audit_glyphs

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
SCALE=8
def color(value):
 if isinstance(value,(float,int)):return (round(value*255),)*3
 if isinstance(value,(tuple,list)) and len(value)==3:return tuple(round(v*255) for v in value)
 raise AssertionError('Unqualified rectangle color')

def stroke_audit(reference,candidate,palette,output):
 results=[]
 with pdfplumber.open(reference) as left,pdfplumber.open(candidate) as right:
  for number,(r,c) in enumerate(zip(left.pages,right.pages),1):
   for page in [r,c]:
    assert not any((shape.get('fill') and color(shape['non_stroking_color']) in palette) or (shape.get('stroke') and color(shape['stroking_color']) in palette) for shape in page.curves+page.lines),'Unqualified border primitive in exported PDF'
    assert not any(shape.get('stroke') and color(shape['stroking_color']) in palette for shape in page.rects),'Unqualified stroked border rectangle'
   pages=[p.rects for p in [r,c]]
   borders=[rect for rects in pages for rect in rects if rect.get('fill') and color(rect['non_stroking_color']) in palette]
   if not borders:
    results.append(dict(page=number,scalePixelsPerPoint=SCALE,tolerancePoints=.25,bothPagesWithoutBorders=True,colors=[]))
    continue
   x0=min(rect['x0'] for rect in borders)-2;y0=min(rect['top'] for rect in borders)-2
   x1=max(rect['x1'] for rect in borders)+2;y1=max(rect['bottom'] for rect in borders)+2
   size=(math.ceil((x1-x0)*SCALE),math.ceil((y1-y0)*SCALE))
   masks=[]
   areas=[]
   for rects in pages:
    # Coordinate compression integrates the exported vectors exactly. Counting
    # raster samples would bias a rounded 0.48-point Word stroke against 0.5.
    painted=[rect for rect in rects if rect.get('fill') and rect['x1']>x0 and rect['x0']<x1 and rect['bottom']>y0 and rect['top']<y1]
    xs=sorted({x0,x1}|{max(x0,min(x1,rect[k])) for rect in painted for k in ['x0','x1']})
    ys=sorted({y0,y1}|{max(y0,min(y1,rect[k])) for rect in painted for k in ['top','bottom']})
    assert len(xs)*len(ys)<10_000_000,'Border audit grid exceeds its budget'
    xi={v:i for i,v in enumerate(xs)};yi={v:i for i,v in enumerate(ys)};indices={rgb:i+1 for i,rgb in enumerate(sorted(palette))}
    grid=np.zeros((len(ys)-1,len(xs)-1),dtype=np.uint8)
    for rect in painted:
     grid[yi[max(y0,rect['top'])]:yi[min(y1,rect['bottom'])],xi[max(x0,rect['x0'])]:xi[min(x1,rect['x1'])]]=indices.get(color(rect['non_stroking_color']),0)
    cell_areas=np.diff(ys)[:,None]*np.diff(xs)[None,:]
    areas.append({rgb:float(cell_areas[grid==index].sum()) for rgb,index in indices.items()})
    canvas=Image.new('RGB',size,'white');draw=ImageDraw.Draw(canvas)
    for rect in rects:
     if not rect.get('fill'):continue
     rgb=color(rect['non_stroking_color'])
     if rgb not in palette:rgb=(255,255,255)
     box=(round((rect['x0']-x0)*SCALE),round((rect['top']-y0)*SCALE),round((rect['x1']-x0)*SCALE)-1,round((rect['bottom']-y0)*SCALE)-1)
     if box[2]>=box[0] and box[3]>=box[1]:draw.rectangle(box,fill=rgb)
    masks.append(canvas)
   channels=[]
   for rgb in sorted(palette):
    binary=[]
    for canvas in masks:
     difference=ImageChops.difference(canvas,Image.new('RGB',size,rgb))
     red,green,blue=difference.split();maximum=ImageChops.lighter(ImageChops.lighter(red,green),blue)
     binary.append(maximum.point(lambda v:255 if v==0 else 0))
    a,b=binary;ac=a.histogram()[255];bc=b.histogram()[255]
    missing=ImageChops.subtract(a,b.filter(ImageFilter.MaxFilter(5)))
    extra=ImageChops.subtract(b,a.filter(ImageFilter.MaxFilter(5)))
    aa,ba=areas[0][rgb],areas[1][rgb]
    record=dict(rgb=rgb,referencePixels=ac,candidatePixels=bc,referenceAreaSquarePoints=aa,candidateAreaSquarePoints=ba,relativeAreaDifference=abs(aa-ba)/max(aa,1),missingBeyondQuarterPoint=missing.histogram()[255],extraBeyondQuarterPoint=extra.histogram()[255])
    if record['missingBeyondQuarterPoint'] or record['extraBeyondQuarterPoint']:
     ImageChops.lighter(missing,extra).save(output.parent/f'{output.stem}-border-difference-{number}-{rgb[0]}-{rgb[1]}-{rgb[2]}.png')
    channels.append(record)
   results.append(dict(page=number,scalePixelsPerPoint=SCALE,tolerancePoints=.25,colors=channels))
 return results

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ['source','reference','candidate','output']:parser.add_argument(name,type=Path)
 args=parser.parse_args()
 result=audit_glyphs(args.source,args.reference,args.candidate)
 with ZipFile(args.source) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 palette={tuple(bytes.fromhex(n.get(W+'color'))) for n in body.iter() if n.tag in {W+x for x in ['top','right','bottom','left','insideH','insideV']} and n.get(W+'val')=='single'}
 result['borders']=stroke_audit(args.reference,args.candidate,palette,args.output)
 args.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
 print(json.dumps(result['borders']),flush=True)
 assert all(c['relativeAreaDifference']<=.05 and c['missingBeyondQuarterPoint']==0 and c['extraBeyondQuarterPoint']==0 for p in result['borders'] for c in p['colors']),'Visible border footprint differs from Word'

if __name__=='__main__':main()
