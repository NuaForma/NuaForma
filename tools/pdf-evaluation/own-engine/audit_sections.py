"""Independently verify next-page ranges, source inventory, geometry, bands and global numbering."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile
import pdfplumber
import pypdfium2 as pdfium
from compare import geometric_lines

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
HEADERS=['Code','Werkzaamheden','Aantal','Bedrag']
def words(text):return Counter(re.findall(r'\w+|[^\w\s]',text))

def source_ranges(path):
    with ZipFile(path) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
    result=[];content=[]
    for node in body:
        if node.tag==W+'sectPr':geometry=node
        else:
            content.append(node);geometry=node.find(W+'pPr/'+W+'sectPr') if node.tag==W+'p' else None
            if geometry is None:continue
        size=geometry.find(W+'pgSz');margins=geometry.find(W+'pgMar')
        result.append(dict(dimensions=[int(size.get(W+'w'))/20,int(size.get(W+'h'))/20],
            top=int(margins.get(W+'top'))/20,bottom=int(margins.get(W+'bottom'))/20,
            inventory=words(' '.join(''.join(t.text or '' for t in p.iter(W+'t')) for block in content for p in block.iter(W+'p')))))
        content=[]
    assert len(result)==3 and not content
    return result

def inspect(path,ranges):
    pdf=pdfium.PdfDocument(path);inventories=[Counter() for _ in ranges];page_ranges=[];geometry=[];images=[]
    try:
        for i in range(len(pdf)):
            page=pdf[i];text=page.get_textpage()
            try:
                matching=[n for n,r in enumerate(ranges) if abs(r['dimensions'][0]-page.get_width())<.1 and abs(r['dimensions'][1]-page.get_height())<.1]
                assert len(matching)==1,'Page dimensions do not match a source range'
                n=matching[0];r=ranges[n];page_ranges.append(n)
                inventories[n]+=words(text.get_text_bounded(left=0,bottom=r['bottom'],right=page.get_width(),top=page.get_height()-r['top']))
                assert re.findall(r'\bPagina\s+(\d+)\b',text.get_text_bounded())==[str(i+1)],'Page field numbering restarted, duplicated or disappeared'
            finally:text.close();page.close()
    finally:pdf.close()
    assert sorted(page_ranges)==page_ranges and set(page_ranges)==set(range(len(ranges))),'Missing or reordered content range'
    for actual,r in zip(inventories,ranges):
        expected=r['inventory'];extra=actual[HEADERS[0]]-expected[HEADERS[0]]
        assert extra>=0 and all(actual[h]-expected[h]==extra for h in HEADERS),'Repeated header counts differ'
        actual.subtract({h:extra for h in HEADERS})
        assert +actual==expected,'Complete source content differs within a physical range'
    with pdfplumber.open(path) as document:
        for page in document.pages:
            assert page.rotation==0
            assert all(0<=c['x0']<=c['x1']<=page.width and 0<=c['top']<=c['bottom']<=page.height for c in page.chars if c['text'].strip()),'Outside or clipped glyph'
            geometry.append(geometric_lines(page))
            assert len(page.images)==1,'Missing or duplicated page-band logo'
            images.append({k:page.images[0][k] for k in ['x0','top','width','height']})
    return page_ranges,geometry,images

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:p.add_argument(name,type=Path)
    a=p.parse_args();receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    ranges=source_ranges(a.source);r,rl,ri=inspect(a.reference,ranges);c,cl,ci=inspect(a.candidate,ranges)
    assert r==c,'Pagination or physical range boundaries differ from Word'
    pages=[]
    for n,(left,right) in enumerate(zip(rl,cl),1):
        assert [v['text'] for v in left]==[v['text'] for v in right],f'Page {n}: line text or wrapping differs'
        dx=max(abs(i['x']-j['x']) for i,j in zip(left,right));dy=max(abs(i['y']-j['y']) for i,j in zip(left,right))
        assert dx<1 and dy<1,(n,dx,dy)
        assert all(abs(ri[n-1][k]-ci[n-1][k])<.1 for k in ri[n-1]),'Logo physical geometry differs'
        pages.append(dict(page=n,range=c[n-1]+1,dimensions=ranges[c[n-1]]['dimensions'],lines=len(left),maxLineStartDeltaPoints=dx,maxBaselineDeltaPoints=dy))
    result=dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),pageCount=len(c),pages=pages)
    a.output.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
