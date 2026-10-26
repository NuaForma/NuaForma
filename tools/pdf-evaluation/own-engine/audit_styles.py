"""Verify source text, effective font faces/colors and geometry against actual Word bytes."""
import argparse,hashlib,json
from pathlib import Path
from types import SimpleNamespace
from xml.etree import ElementTree as ET
from zipfile import ZipFile
import pdfplumber
from compare import geometric_lines,normalized
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def inspect(path,top,bottom):
    result=[]
    with pdfplumber.open(path) as pdf:
        for p in pdf.pages:
            chars=[c for c in p.chars if c['top']>=top-.5 and c['bottom']<=p.height-bottom+.5]
            lines=geometric_lines(SimpleNamespace(chars=chars,height=p.height))
            glyphs=[]
            for line in lines:
                row=sorted([c for c in chars if abs(float(c['matrix'][5])-line['y'])<.2],key=lambda c:c['x0'])
                for c in row:
                    face=c['fontname'].lower();color=c.get('non_stroking_color')
                    color=tuple(color) if isinstance(color,(list,tuple)) else (color,)*3
                    if len(color)==1:color=color*3
                    for ch in c['text']:
                        if not ch.isspace():glyphs.append(dict(text=ch,bold='bold' in face,italic='italic' in face,color=color,size=c['size']))
            assert all(0<=c['x0']<=c['x1']<=p.width and 0<=c['top']<=c['bottom']<=p.height for c in p.chars if c['text'].strip()),'Outside glyph'
            result.append(dict(lines=[dict(text=l['text'],x=l['x'],baselineTop=p.height-l['y']) for l in lines],glyphs=glyphs))
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:parser.add_argument(name,type=Path)
    a=parser.parse_args()
    receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    with ZipFile(a.source) as z:root=ET.fromstring(z.read('word/document.xml'))
    margins=root.find(W+'body/'+W+'sectPr/'+W+'pgMar');top=int(margins.get(W+'top'))/20;bottom=int(margins.get(W+'bottom'))/20
    expected=normalized(' '.join(''.join(t.text or '' for t in p.iter(W+'t')) for p in root.find(W+'body').iter(W+'p')))
    reference=inspect(a.reference,top,bottom);candidate=inspect(a.candidate,top,bottom)
    assert len(reference)==len(candidate)
    for pages in [reference,candidate]:assert normalized(' '.join(l['text'] for p in pages for l in p['lines']))==expected,'Source text differs'
    results=[]
    for n,(r,c) in enumerate(zip(reference,candidate),1):
        assert [l['text'] for l in r['lines']]==[l['text'] for l in c['lines']],'Line text differs'
        assert len(r['glyphs'])==len(c['glyphs'])
        for x,y in zip(r['glyphs'],c['glyphs']):
            assert (x['text'],x['bold'],x['italic'])==(y['text'],y['bold'],y['italic']),'Effective font differs'
            assert len(x['color'])==len(y['color'])==3
            assert max(abs(i-j) for i,j in zip(x['color'],y['color']))<.005,'Effective color differs'
            assert abs(x['size']-y['size'])<.05,'Effective size differs'
        dx=max(abs(x['x']-y['x']) for x,y in zip(r['lines'],c['lines']))
        dy=max(abs(x['baselineTop']-y['baselineTop']) for x,y in zip(r['lines'],c['lines']))
        assert dx<1 and dy<1,(dx,dy)
        results.append(dict(page=n,lines=len(r['lines']),glyphs=len(r['glyphs']),sameSourceText=True,sameEffectiveFacesAndColors=True,maxStartDelta=dx,maxBaselineDelta=dy))
    a.output.write_text(json.dumps(dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),pages=results),indent=2),encoding='utf-8')
    print(json.dumps(results))

if __name__=='__main__':main()
