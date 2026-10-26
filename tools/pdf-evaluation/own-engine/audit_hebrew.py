"""Independently check the bounded Hebrew proof's logical text, search and visual glyphs."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile
import pypdfium2 as pdfium
from audit_styles import inspect as glyph_inspect
from compare import normalized

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
PHRASES=['שלום עולם', 'הפרויקט', 'העבודה', '123.45', 'יחידות', 'המסמכים']


def inspect(path, top, bottom):
    pdf=pdfium.PdfDocument(path); pages=[]; found=set()
    try:
        for n in range(len(pdf)):
            page=pdf[n]; text=page.get_textpage()
            try:
                pages.append(normalized(text.get_text_bounded(left=0,bottom=bottom,right=page.get_width(),top=page.get_height()-top)))
                for phrase in PHRASES:
                    search=text.search(phrase)
                    try:
                        if search.get_next() is not None: found.add(phrase)
                    finally: search.close()
            finally: text.close();page.close()
    finally: pdf.close()
    assert found==set(PHRASES), f'Search failed: {set(PHRASES)-found}'
    return dict(bodyByPage=pages, searches=sorted(found))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:p.add_argument(name,type=Path)
    a=p.parse_args()
    receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    with ZipFile(a.source) as z: body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
    margin=body.find(W+'sectPr/'+W+'pgMar'); top=int(margin.get(W+'top'))/20; bottom=int(margin.get(W+'bottom'))/20
    expected=normalized(' '.join(''.join(t.text or '' for t in paragraph.iter(W+'t')) for paragraph in body.iter(W+'p')))
    reference=inspect(a.reference,top,bottom); candidate=inspect(a.candidate,top,bottom)
    words=lambda text: Counter(re.findall(r'[^\W\d_]+|\d+(?:\.\d+)?',text))
    expected_words=words(expected)
    for label,pdf in [('reference',reference),('candidate',candidate)]:
        actual=words(' '.join(pdf['bodyByPage']))
        # Only the three generated numbering tokens are absent from the DOCX text nodes.
        actual.subtract({'1':1,'2':1,'3':1});actual=+actual
        assert actual==expected_words, f'{label}: source word inventory differs'
    assert [words(page) for page in reference['bodyByPage']]==[words(page) for page in candidate['bodyByPage']], 'Page assignment differs'
    # Visual order is checked from physical glyph coordinates, independently of copy order.
    r=glyph_inspect(a.reference,0,0); c=glyph_inspect(a.candidate,0,0); results=[]
    assert len(r)==len(c)
    for n,(x,y) in enumerate(zip(r,c),1):
        visual=lambda page: [re.sub(r'\s+','',l['text']) for l in page['lines']]
        assert visual(x)==visual(y), f'Page {n}: visual line glyph order differs'
        assert len(x['glyphs'])==len(y['glyphs'])
        for left,right in zip(x['glyphs'],y['glyphs']):
            assert (left['text'],left['bold'],left['italic'])==(right['text'],right['bold'],right['italic']),f'Page {n}: glyph or face differs'
            assert abs(left['size']-right['size'])<.05,'Font size differs'
            assert max(abs(i-j) for i,j in zip(left['color'],right['color']))<.005,'Color differs'
        dx=max(abs(i['x']-j['x']) for i,j in zip(x['lines'],y['lines']))
        dy=max(abs(i['baselineTop']-j['baselineTop']) for i,j in zip(x['lines'],y['lines']))
        assert dx<1 and dy<1,(n,dx,dy)
        results.append(dict(page=n,lines=len(x['lines']),glyphs=len(x['glyphs']),maxLineStartDeltaPoints=dx,maxBaselineDeltaPoints=dy))
    result=dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),sourceWordInventoryPreserved=True,pdfiumLogicalOrderQualified=False,reference=reference,candidate=candidate,pages=results)
    a.output.write_text(json.dumps(result,ensure_ascii=True,indent=2),encoding='utf-8');print(json.dumps(dict(sourceWordInventoryPreserved=True,pdfiumLogicalOrderQualified=False,searches=len(PHRASES),pages=results)))


if __name__=='__main__':main()
