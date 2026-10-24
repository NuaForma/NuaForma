"""Independent PDFium source-text and search checks for the qualified LTR script proof."""
import argparse,hashlib,json,re
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pypdfium2 as pdfium
from pypdf import PdfReader
from pypdf.generic import ContentStream
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def normalize(text):return re.sub(r'\s+',' ',text).strip()

def inspect(path,source,margins):
    pdf=pdfium.PdfDocument(path);pages=[];found=set()
    try:
        for n in range(len(pdf)):
            page=pdf[n];text=page.get_textpage()
            try:
                body=normalize(text.get_text_bounded(left=0,bottom=margins[1],right=page.get_width(),top=page.get_height()-margins[0]))
                pages.append(body)
                for phrase in ['Київ','Україна','Έκθεση','Документы']:
                    search=text.search(phrase)
                    try:
                        if search.get_next() is not None:found.add(phrase)
                    finally:search.close()
            finally:text.close();page.close()
    finally:pdf.close()
    actual=normalize(' '.join(pages));actual=re.sub(r'\b[123]\.\s+(?=L0[123]\b)','',actual)
    assert actual==source,'PDFium output differs from complete source body'
    assert found=={'Київ','Україна','Έκθεση','Документы'},'A source-script search failed'
    return dict(pageCount=len(pages),bodyByPage=pages,searches=sorted(found))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['source','reference','candidate','output']:p.add_argument(name,type=Path)
    a=p.parse_args();receipt=json.loads(Path(str(a.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    assert receipt['sourceSha256']==hashlib.sha256(a.source.read_bytes()).hexdigest();assert receipt['pdfSha256']==hashlib.sha256(a.reference.read_bytes()).hexdigest()
    with ZipFile(a.source) as z:root=ET.fromstring(z.read('word/document.xml'))
    body=root.find(W+'body');margin=body.find(W+'sectPr/'+W+'pgMar');margins=int(margin.get(W+'top'))/20,int(margin.get(W+'bottom'))/20
    source=normalize(' '.join(''.join(t.text or '' for t in paragraph.iter(W+'t')) for paragraph in body.iter(W+'p')))
    reference=inspect(a.reference,source,margins);candidate=inspect(a.candidate,source,margins)
    assert reference['bodyByPage']==candidate['bodyByPage'],'PDFium page content differs'
    tags=set()
    for page in PdfReader(a.candidate).pages:
        depth=0
        for operands,operation in ContentStream(page['/Contents'],page.pdf).operations:
            if operation in (b'BMC',b'BDC'):depth+=1
            if operation==b'BDC' and isinstance(operands[1],dict) and '/Lang' in operands[1]:tags.add(str(operands[1]['/Lang']))
            if operation==b'EMC':depth-=1;assert depth>=0,'Unbalanced marked content'
        assert depth==0,'Unclosed language scope'
    assert tags=={'en-US','ru-RU','uk-UA','el-GR'},'Language metadata differs'
    result=dict(sourceSha256=receipt['sourceSha256'],referenceSha256=receipt['pdfSha256'],candidateSha256=hashlib.sha256(a.candidate.read_bytes()).hexdigest(),reference=reference,candidate=candidate,languageTags=sorted(tags))
    a.output.write_text(json.dumps(result,ensure_ascii=True,indent=2),encoding='utf-8');print(json.dumps(dict(pages=candidate['pageCount'],sourceTextPreserved=True,searches=len(candidate['searches']),languageTags=sorted(tags))))

if __name__=='__main__':main()
