"""Independent source inventory and glyph geometry for qualified cell-alignment fixtures."""
import argparse,hashlib,json
from collections import Counter
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def inventory(text):return Counter(ch for ch in text if not ch.isspace())
def rgb(value):
 values=tuple(value) if isinstance(value,(tuple,list)) else (value,)
 return values*3 if len(values)==1 else values
def inspect(path,top,bottom):
 result=[]
 with pdfplumber.open(path) as pdf:
  for page in pdf.pages:
   chars=[c for c in page.chars if c['text'].strip()]
   assert all(0<=c['x0']<=c['x1']<=page.width and 0<=c['top']<=c['bottom']<=page.height for c in chars),'Glyph outside page'
   result.append(dict(width=page.width,height=page.height,chars=chars,body=''.join(c['text'] for c in chars if top<=c['top']<page.height-bottom),images=page.images))
 return result

def audit(source,reference,candidate,*,all_table_headers=False):
 receipt=json.loads(Path(str(reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
 assert receipt['sourceSha256']==digest(source) and receipt['pdfSha256']==digest(reference),'Reference provenance mismatch'
 with ZipFile(source) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 section=body.find(W+'sectPr');margins=section.find(W+'pgMar');size=section.find(W+'pgSz')
 top=int(margins.get(W+'top'))/20;bottom=int(margins.get(W+'bottom'))/20
 expected=inventory(''.join(t.text or '' for t in body.iter(W+'t')))
 header=body.find(W+'tbl/'+W+'tr')
 repeated=inventory(''.join(t.text or '' for t in header.iter(W+'t')))
 if all_table_headers:
  leading=[]
  for row in body.find(W+'tbl').findall(W+'tr'):
   if row.find(W+'trPr/'+W+'tblHeader') is None:break
   leading.extend(row.iter(W+'t'))
  repeated=inventory(''.join(t.text or '' for t in leading))
 left=inspect(reference,top,bottom);right=inspect(candidate,top,bottom)
 assert len(left)==len(right)
 repeats=len(left)-1
 if all_table_headers:
  header_text=''.join(t.text or '' for t in leading)
  header_key=''.join(ch for ch in header_text if not ch.isspace())
  assert header_key and ''.join(ch for t in body.iter(W+'t') for ch in (t.text or '') if not ch.isspace()).count(header_key)==1,'Ambiguous source table header'
  counts=[p['body'].count(header_key) for p in left]
  assert all(n<=1 for n in counts) and sum(counts)>=1,'Ambiguous Word header repetitions'
  repeats=sum(counts)-1
 for pages in [left,right]:
  actual=inventory(''.join(p['body'] for p in pages))
  assert actual==expected+Counter({ch:n*repeats for ch,n in repeated.items()}),'Source content lost or duplicated'
 results=[]
 for number,(r,c) in enumerate(zip(left,right),1):
  assert abs(r['width']-c['width'])<.05 and abs(r['height']-c['height'])<.05
  assert abs(c['width']-int(size.get(W+'w'))/20)<.05 and abs(c['height']-int(size.get(W+'h'))/20)<.05
  assert ''.join(x['text'] for x in r['chars'])==''.join(x['text'] for x in c['chars']),'Glyph reading order differs'
  assert len(r['chars'])==len(c['chars'])
  dx=dy=0
  for a,b in zip(r['chars'],c['chars']):
   dx=max(dx,abs(a['x0']-b['x0']))
   dy=max(dy,abs((r['height']-float(a['matrix'][5]))-(c['height']-float(b['matrix'][5]))))
   assert a['fontname'].split('+')[-1].lower()==b['fontname'].split('+')[-1].lower(),'Font face differs'
   assert abs(a['size']-b['size'])<.05,'Font size differs'
   ac=rgb(a['non_stroking_color']);bc=rgb(b['non_stroking_color'])
   assert len(ac)==len(bc)==3 and max(abs(x-y) for x,y in zip(ac,bc))<.005,'Text color differs'
  assert dx<1 and dy<1,(number,dx,dy)
  assert len(r['images'])==len(c['images'])==1
  for a,b in zip(r['images'],c['images']):assert max(abs(a[k]-b[k]) for k in ['x0','top','width','height'])<.1,'Logo geometry differs'
  results.append(dict(page=number,glyphs=len(r['chars']),sameSourceInventory=True,sameGlyphOrder=True,sameFacesSizesAndColors=True,maxGlyphStartDeltaPoints=dx,maxBaselineDeltaPoints=dy))
 return dict(sourceSha256=digest(source),referenceSha256=digest(reference),candidateSha256=digest(candidate),wordBuild=receipt['build'],pages=results)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ['source','reference','candidate','output']:parser.add_argument(name,type=Path)
 args=parser.parse_args();result=audit(args.source,args.reference,args.candidate)
 args.output.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result['pages']))
