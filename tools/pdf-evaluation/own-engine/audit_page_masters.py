"""Independently verify selected/blank page bands, inherited stories, body inventory and geometry."""
import argparse
from collections import Counter
import hashlib,json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile
import pdfplumber
import pypdfium2 as pdfium
from audit_sections import source_ranges,words,HEADERS,W
from compare import geometric_lines

R='{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
P='{http://schemas.openxmlformats.org/package/2006/relationships}'
def enabled(node):return node is not None and node.get(W+'val') not in ['false','0','off']

def profiles(path):
    with ZipFile(path) as z:parts={name:z.read(name) for name in z.namelist()}
    relations={node.get('Id'):node.get('Target') for node in ET.fromstring(parts['word/_rels/document.xml.rels']).findall(P+'Relationship')}
    even=enabled(ET.fromstring(parts['word/settings.xml']).find(W+'evenAndOddHeaders'))
    references={'header':{},'footer':{}};result=[]
    for section in ET.fromstring(parts['word/document.xml']).iter(W+'sectPr'):
        for name in references:
            for node in section.findall(W+name+'Reference'):references[name][node.get(W+'type')]=relations[node.get(R+'id')]
        stories={kind:[ET.fromstring(parts['word/'+references[name][kind]]) if kind in references[name] else None for name in ['header','footer']] for kind in ['default','first','even']}
        result.append(dict(first=enabled(section.find(W+'titlePg')),even=even,stories=stories))
    return result

def band_text(story,number):
    if story is None:return ''
    def visit(node):
        if node.tag==W+'fldSimple':
            assert node.get(W+'instr').strip()=='PAGE'
            return str(number)
        if node.tag==W+'t':return node.text or ''
        return ''.join(visit(child) for child in node)
    return ' '.join(visit(paragraph) for paragraph in story.iter(W+'p'))

def inspect(path,ranges,policies):
    pdf=pdfium.PdfDocument(path);counts=[0]*len(ranges);inventory=[Counter() for _ in ranges];assignment=[];selected=[]
    try:
        for i in range(len(pdf)):
            page=pdf[i];text=page.get_textpage()
            try:
                matches=[n for n,r in enumerate(ranges) if abs(r['dimensions'][0]-page.get_width())<.1 and abs(r['dimensions'][1]-page.get_height())<.1]
                assert len(matches)==1,'Unknown physical page policy'
                n=matches[0];r=ranges[n];policy=policies[n];assignment.append(n)
                kind='first' if counts[n]==0 and policy['first'] else 'even' if (i+1)%2==0 and policy['even'] else 'default'
                counts[n]+=1;header,footer=policy['stories'][kind]
                inventory[n]+=words(text.get_text_bounded(left=0,bottom=r['bottom'],right=page.get_width(),top=page.get_height()-r['top']))
                actual_header=text.get_text_bounded(left=0,bottom=page.get_height()-r['top'],right=page.get_width(),top=page.get_height())
                actual_footer=text.get_text_bounded(left=0,bottom=0,right=page.get_width(),top=r['bottom'])
                assert words(actual_header)==words(band_text(header,i+1)),f'Page {i+1}: wrong, missing or nonblank header'
                assert words(actual_footer)==words(band_text(footer,i+1)),f'Page {i+1}: wrong footer or page numbering'
                images=sum(len(list(story.iter(W+'drawing'))) for story in [header,footer] if story is not None)
                selected.append(dict(kind=kind,header=header is not None,footer=footer is not None,images=images))
            finally:text.close();page.close()
    finally:pdf.close()
    assert sorted(assignment)==assignment and set(assignment)==set(range(len(ranges)))
    for actual,r in zip(inventory,ranges):
        expected=r['inventory'];extra=actual[HEADERS[0]]-expected[HEADERS[0]]
        assert extra>=0 and all(actual[h]-expected[h]==extra for h in HEADERS)
        actual.subtract({h:extra for h in HEADERS});assert +actual==expected,'Complete range body inventory differs'
    lines=[];image_bounds=[]
    with pdfplumber.open(path) as document:
        for i,page in enumerate(document.pages):
            assert page.rotation==0
            assert all(0<=c['x0']<=c['x1']<=page.width and 0<=c['top']<=c['bottom']<=page.height for c in page.chars if c['text'].strip()),'Clipped or outside glyph'
            assert len(page.images)==selected[i]['images'],'Missing or unwanted page-band logo'
            lines.append(geometric_lines(page));image_bounds.append([{k:image[k] for k in ['x0','top','width','height']} for image in page.images])
    return assignment,selected,lines,image_bounds

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:p.add_argument(name,type=Path)
    a=p.parse_args();receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    ranges=source_ranges(a.source);policies=profiles(a.source)
    r,rs,rl,ri=inspect(a.reference,ranges,policies);c,cs,cl,ci=inspect(a.candidate,ranges,policies)
    assert r==c and rs==cs,'Range pagination or band selection differs from Word';pages=[]
    for n,(left,right) in enumerate(zip(rl,cl),1):
        assert [v['text'] for v in left]==[v['text'] for v in right],f'Page {n}: line text or wrapping differs'
        dx=max(abs(i['x']-j['x']) for i,j in zip(left,right));dy=max(abs(i['y']-j['y']) for i,j in zip(left,right))
        assert dx<1 and dy<1,(n,dx,dy)
        assert all(abs(x[k]-y[k])<.1 for x,y in zip(ri[n-1],ci[n-1]) for k in x),'Logo physical geometry differs'
        pages.append(dict(page=n,range=c[n-1]+1,**cs[n-1],lines=len(left),maxLineStartDeltaPoints=dx,maxBaselineDeltaPoints=dy))
    result=dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),wordBuild=receipt['build'],pageCount=len(c),pages=pages)
    a.output.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
