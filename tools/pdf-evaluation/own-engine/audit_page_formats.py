"""Check source dimensions, body inventory, search, bounds and Word geometry per paper size."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber
import pypdfium2 as pdfium
from compare import geometric_lines

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
HEADERS=['Code','Werkzaamheden','Aantal','Bedrag']
PHRASES=['Київ','Україна','Отчёт','Έκθεση']


def words(text):return Counter(re.findall(r'\w+|[^\w\s]',text))


def inspect(path,width,height,margins,expected):
    pages=[];found=set(); pdf=pdfium.PdfDocument(path)
    try:
        for n in range(len(pdf)):
            page=pdf[n]; text=page.get_textpage()
            try:
                assert abs(page.get_width()-width)<.1 and abs(page.get_height()-height)<.1,'Physical page dimensions differ'
                pages.append(text.get_text_bounded(left=0,bottom=margins['bottom'],right=page.get_width(),top=page.get_height()-margins['top']))
                for phrase in PHRASES:
                    search=text.search(phrase)
                    try:
                        if search.get_next() is not None:found.add(phrase)
                    finally:search.close()
            finally:text.close();page.close()
    finally:pdf.close()
    actual=words(' '.join(pages));repetitions=actual[HEADERS[0]]-expected[HEADERS[0]]
    assert repetitions>=0 and all(actual[h]-expected[h]==repetitions for h in HEADERS),'Repeated header inventory differs'
    actual.subtract({h:repetitions for h in HEADERS})
    assert +actual==expected,'Complete source body inventory differs'
    assert found==set(PHRASES),'A source-script search failed'
    geometry=[]
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            assert page.rotation==0,'Unexpected page rotation'
            assert all(0<=c['x0']<=c['x1']<=page.width and 0<=c['top']<=c['bottom']<=page.height for c in page.chars if c['text'].strip()),'Clipped or outside glyph'
            geometry.append(geometric_lines(page))
    return dict(pageCount=len(pages),repeatedHeaderCopies=repetitions,searches=sorted(found)),geometry


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:p.add_argument(name,type=Path)
    p.add_argument('--allow-one-word-wrap',action='store_true',help='Permit one word to move between two adjacent lines on one page, retaining all line positions and page content')
    a=p.parse_args();receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    with ZipFile(a.source) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
    size=body.find(W+'sectPr/'+W+'pgSz');width=int(size.get(W+'w'))/20;height=int(size.get(W+'h'))/20
    node=body.find(W+'sectPr/'+W+'pgMar');margins={edge:int(node.get(W+edge))/20 for edge in ['top','right','bottom','left']}
    expected=words(' '.join(''.join(t.text or '' for t in paragraph.iter(W+'t')) for paragraph in body.iter(W+'p')))
    reference,r=inspect(a.reference,width,height,margins,expected);candidate,c=inspect(a.candidate,width,height,margins,expected)
    assert reference['pageCount']==candidate['pageCount'];results=[];wrap_pages=0
    for n,(left,right) in enumerate(zip(r,c),1):
        assert len(left)==len(right),f'Page {n}: line count differs'
        changed=[i for i,(x,y) in enumerate(zip(left,right)) if x['text']!=y['text']];wrap=None
        if changed:
            assert a.allow_one_word_wrap and len(changed)==2 and changed[1]==changed[0]+1,f'Page {n}: unexpected line content or wrapping difference'
            wrap_pages+=1;assert wrap_pages==1,'More than one page has a wrap difference'
            i=changed[0];before=[left[j]['text'].split() for j in [i,i+1]];after=[right[j]['text'].split() for j in [i,i+1]]
            if before[1] and after==[before[0]+before[1][:1],before[1][1:]]:wrap=dict(word=before[1][0],candidateLine='earlier',lines=[i+1,i+2])
            elif before[0] and after==[before[0][:-1],before[0][-1:]+before[1]]:wrap=dict(word=before[0][-1],candidateLine='later',lines=[i+1,i+2])
            else:raise AssertionError(f'Page {n}: more than a single adjacent word moved')
        dx=max(abs(i['x']-j['x']) for i,j in zip(left,right));dy=max(abs(i['y']-j['y']) for i,j in zip(left,right))
        assert dx<1 and dy<1,(n,dx,dy)
        results.append(dict(page=n,lines=len(left),maxLineStartDeltaPoints=dx,maxBaselineDeltaPoints=dy,oneWordWrap=wrap))
    result=dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),dimensions=dict(width=width,height=height),margins=margins,reference=reference,candidate=candidate,pages=results)
    a.output.write_text(json.dumps(result,ensure_ascii=True,indent=2),encoding='utf-8');print(json.dumps(dict(dimensions=result['dimensions'],reference=reference,candidate=candidate,pages=results)))


if __name__=='__main__':main()
