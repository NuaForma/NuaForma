"""Add inert printer tray selection without changing paper dimensions or source content."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:
            parts={name:z.read(name) for name in z.namelist()}
        document=parts['word/document.xml'].decode()
        assert 'paperSrc' not in document and document.count('</w:sectPr>')==1
        tray='<w:paperSrc w:first="15" w:other="15"/>' if paper=='a4' else '<w:paperSrc w:first="256" w:other="65535"/>'
        parts['word/document.xml']=document.replace('</w:sectPr>',tray+'</w:sectPr>').encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
