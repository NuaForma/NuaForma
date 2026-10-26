"""Compare character positions in rotation-local coordinates and decode full-page barcodes."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
import importlib.metadata
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber
from PIL import Image


def characters(page):
    groups=defaultdict(list)
    for char in page.chars:
        if not char['text'].strip():continue
        matrix=char['matrix'];angle=round(math.degrees(math.atan2(matrix[1],matrix[0])))
        c=math.cos(math.radians(angle));s=math.sin(math.radians(angle))
        x=matrix[4];y=matrix[5]-page.height
        groups[angle].append(dict(text=char['text'],x=x,y=y,along=x*c+y*s,cross=-x*s+y*c))
    result={}
    for angle, chars in groups.items():
        lines=[]
        for char in sorted(chars,key=lambda v:-v['cross']):
            line=next((line for line in lines if abs(line[0]['cross']-char['cross'])<.25),None)
            if line is None:lines.append([char])
            else:line.append(char)
        result[angle]=[char for line in lines for char in sorted(line,key=lambda v:v['along'])]
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['reference','candidate','output','source']:parser.add_argument(name,type=Path)
    parser.add_argument('--poppler-dir',type=Path,required=True)
    parser.add_argument('--decoder-directory',type=Path)
    parser.add_argument('--allow-graphic-reference-text',action='store_true',help='Verify source text retained at 45 degrees when Word exports it as graphics')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    provenance=json.loads(Path(str(args.reference)+'.provenance.json').read_text(encoding='utf-8-sig'))
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    assert digest(args.source)==provenance['sourceSha256'] and digest(args.reference)==provenance['pdfSha256']
    result=dict(sourceSha256=digest(args.source),referenceSha256=digest(args.reference),candidateSha256=digest(args.candidate),pages=[])
    with pdfplumber.open(args.reference) as left,pdfplumber.open(args.candidate) as right:
        assert len(left.pages)==len(right.pages)
        for index,(a,b) in enumerate(zip(left.pages,right.pages),1):
            before,after=characters(a),characters(b);angles=[]
            if args.allow_graphic_reference_text:
                assert index==1 and -45 not in before and -45 in after
                with ZipFile(args.source) as package:source=ET.fromstring(package.read('word/document.xml'))
                ns=dict(wps='http://schemas.microsoft.com/office/word/2010/wordprocessingShape',a='http://schemas.openxmlformats.org/drawingml/2006/main',w='http://schemas.openxmlformats.org/wordprocessingml/2006/main')
                shapes=[shape for shape in source.findall('.//wps:wsp',ns) if shape.find('wps:spPr/a:xfrm',ns).get('rot')=='2700000']
                assert len(shapes)==1
                expected=''.join(node.text or '' for node in shapes[0].findall('.//w:t',ns))
                actual=''.join(c['text'] for c in after.pop(-45))
                assert actual==''.join(expected.split()),'Candidate did not preserve the outlined reference text'
                result['referenceTextAsGraphics']=dict(source=expected,candidatePreservesSearchableText=True,wordHasSearchable45DegreeText=False)
            assert Counter(v['text'] for chars in before.values() for v in chars)==Counter(v['text'] for chars in after.values() for v in chars),'Missing/duplicated character content'
            assert before.keys()==after.keys(),'Different text rotation groups'
            for angle in sorted(before):
                source,target=before[angle],after[angle]
                same=[v['text'] for v in source]==[v['text'] for v in target]
                record=dict(degrees=angle,referenceCharacters=len(source),candidateCharacters=len(target),sameGeometricCharacterOrder=same)
                if same:
                    record.update(maxHorizontalOriginDeltaPoints=max(abs(y['x']-x['x']) for x,y in zip(source,target)),maxTopOriginDeltaPoints=max(abs(y['y']-x['y']) for x,y in zip(source,target)))
                angles.append(record)
            result['pages'].append(dict(page=index,angles=angles,candidateGlyphsOutsidePhysicalPage=sum(c['x0']<0 or c['x1']>b.width or c['top']<0 or c['bottom']>b.height for c in b.chars if c['text'].strip())))
    if args.decoder_directory:
        sys.path.insert(0,str(args.decoder_directory.resolve()));import zxingcpp
        result['decoderVersion']=importlib.metadata.version('zxing-cpp')
        result['barcodeDpi']=300;result['barcodes']={}
        renderer=args.poppler_dir/('pdftoppm.exe' if (args.poppler_dir/'pdftoppm.exe').exists() else 'pdftoppm')
        for role,path in [('reference',args.reference),('candidate',args.candidate)]:
            subprocess.run([str(renderer),'-png','-r','300',str(path.resolve()),str((args.output/role).resolve())],check=True,timeout=120)
            scans=[]
            for image in sorted(args.output.glob(role+'-*.png')):
                scans.extend(dict(value=item.text,orientation=item.orientation) for item in zxingcpp.read_barcodes(Image.open(image)))
            assert Counter((x['value'],x['orientation']) for x in scans)==Counter({('NF20260057',90):1,('NF20260057',-90):1}), 'Full-page rotated barcode qualification failed'
            result['barcodes'][role]=scans
    assert all(not page['candidateGlyphsOutsidePhysicalPage'] for page in result['pages'])
    assert all(angle['sameGeometricCharacterOrder'] for page in result['pages'] for angle in page['angles']), 'Geometric text order differs'
    (args.output/'geometry.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
