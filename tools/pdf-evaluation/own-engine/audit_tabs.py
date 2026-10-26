"""Strict source/body/field checks; decorative leader runs are not source content or masks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from types import SimpleNamespace
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber
from compare import geometric_lines, normalized

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def body_chars(page,top,bottom):
    chars=[c for c in page.chars if c['top']>=top-.5 and c['bottom']<=bottom+.5]
    rows={};removed=set()
    for i,c in enumerate(chars):rows.setdefault(round(float(c['matrix'][5])*2),[]).append((i,c))
    for row in rows.values():
        run=[];kind=None
        def flush():
            if len(run)>=3:removed.update(i for i,c in run)
        for i,c in sorted(row,key=lambda pair:pair[1]['x0']):
            t=c['text']
            if t.isspace():continue
            if len(t)==1 and t in '._-':
                if t!=kind:flush();run=[]
                kind=t;run.append((i,c))
            else:flush();run=[];kind=None
        flush()
    return [c for i,c in enumerate(chars) if i not in removed]


def inspect(path,source,expected,paragraphs):
    pages=[];fields={};all_lines=[]
    with pdfplumber.open(path) as pdf:
        for index,page in enumerate(pdf.pages,1):
            chars=body_chars(page,source['top'],page.height-source['bottom'])
            lines=geometric_lines(SimpleNamespace(chars=chars,height=page.height))
            all_lines += [line['text'] for line in lines]
            for marker,segments in paragraphs.items():
                matched=[line for line in lines if line['text'].startswith(marker+' ')]
                if not matched:continue
                assert len(matched)==1 and marker not in fields,'Duplicate tab paragraph'
                line=matched[0]
                row=sorted([c for c in chars if abs(float(c['matrix'][5])-line['y'])<.2],key=lambda c:c['x0'])
                letters=[(letter,c) for c in row for letter in c['text'] if not letter.isspace()]
                cursor=0;positions=[]
                for segment in segments:
                    text=''.join(segment.split());end=cursor+len(text)
                    if cursor<len(letters):
                        fragment=letters[cursor:min(end,len(letters))]
                        assert ''.join(c for c,_ in fragment)==text[:len(fragment)],'Field source order differs'
                        positions.append(dict(text=segment,x=fragment[0][1]['x0'],end=fragment[-1][1]['x1'],complete=end<=len(letters)))
                    cursor=end
                fields[marker]=dict(page=index,baselineTop=page.height-line['y'],fields=positions)
            assert all(0<=c['x0']<=c['x1']<=page.width and 0<=c['top']<=c['bottom']<=page.height for c in page.chars if c['text'].strip()),'Outside glyph'
            pages.append(dict(page=index,bodyText=normalized(' '.join(x['text'] for x in lines))))
    text=normalized(' '.join(all_lines))
    # Word breaks an authored hyphen compound at a line edge; rejoin only literal source tokens.
    tokens=set(expected.split())
    text=re.sub(r'(\S+-)\s+(\S+)',lambda m:m[1]+m[2] if m[1]+m[2] in tokens else m[0],text)
    assert text==expected,'Source body text differs after decorative-run removal'
    assert set(fields)==set(paragraphs),'Missing tab paragraph'
    return dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),pages=pages,fields=fields)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ['source','reference','candidate','output']:parser.add_argument(key,type=Path)
    args=parser.parse_args()
    with ZipFile(args.source) as archive:root=ET.fromstring(archive.read('word/document.xml'))
    body=root.find(W+'body');margins=body.find(W+'sectPr/'+W+'pgMar')
    source=dict(top=int(margins.get(W+'top'))/20,bottom=int(margins.get(W+'bottom'))/20)
    expected=[];paragraphs={}
    for p in body.findall(W+'p'):
        segments=['']
        for run in p.findall(W+'r'):
            for item in run:
                if item.tag==W+'t':segments[-1]+=item.text or ''
                elif item.tag==W+'tab':segments.append('')
        expected.append(normalized(' '.join(segments)))
        if len(segments)>1:paragraphs[segments[0].split()[0]]=segments
    expected=normalized(' '.join(expected))
    assert not re.search(r'[._-]{3,}',expected),'Literal source punctuation conflicts with this audit profile'
    receipt=json.loads(Path(str(args.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(args.source.read_bytes()).hexdigest()
    assert receipt['pdfSha256']==hashlib.sha256(args.reference.read_bytes()).hexdigest()
    reference=inspect(args.reference,source,expected,paragraphs);candidate=inspect(args.candidate,source,expected,paragraphs)
    assert len(reference['pages'])==len(candidate['pages'])
    deltas=[]
    for marker,a in reference['fields'].items():
        b=candidate['fields'][marker];assert a['page']==b['page']
        assert len(a['fields'])==len(b['fields'])
        for x,y in zip(a['fields'],b['fields']):
            delta=dict(marker=marker,text=x['text'],xDelta=y['x']-x['x'],baselineDelta=b['baselineTop']-a['baselineTop'])
            assert abs(delta['xDelta'])<1 and abs(delta['baselineDelta'])<1,delta
            deltas.append(delta)
    result=dict(sourceSha256=receipt['sourceSha256'],reference=reference,candidate=candidate,fieldDeltas=deltas,
                sameSourceBodyText=True,decorativeRunPolicy='Only repeated leader glyphs removed; all pixels remain unmasked; source literals checked')
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(dict(pages=len(candidate['pages']),paragraphs=len(paragraphs),fields=len(deltas),maxXDelta=max(abs(x['xDelta']) for x in deltas),maxBaselineDelta=max(abs(x['baselineDelta']) for x in deltas))))


if __name__=='__main__':main()
