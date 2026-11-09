"""Add explicit default font hints to existing qualified mixed-font fixtures."""
import argparse,re
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'mixed-latin-fonts'/paper/'source.docx') as z:
            parts={name:z.read(name) for name in z.namelist()}
        count=0
        for name,data in list(parts.items()):
            if not name.startswith('word/') or not name.endswith('.xml'):continue
            text=data.decode();assert 'w:hint=' not in text
            changed,n=re.subn(r'<w:rFonts([^>]*)/>',lambda m:'<w:rFonts'+m.group(1)+' w:hint="default"/>',text)
            if n:parts[name]=changed.encode();count+=n
        assert count>=10
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
