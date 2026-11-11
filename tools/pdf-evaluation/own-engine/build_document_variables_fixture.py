"""Build passive document-variable storage without adding any displayed variable field."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:
            parts={name:z.read(name) for name in z.namelist()}
        settings=parts['word/settings.xml'].decode();assert '<w:docVars' not in settings
        values=[('Stored display text','{UNFINISHED_STORED_VARIABLE'),('Stored scripts','Русский العربية 中文 _x0041_ & < > "'),('Empty value','')]
        variable=lambda value:escape(value,{'"':'&quot;'})
        metadata='<w:docVars>'+''.join(f'<w:docVar w:name="{variable(name)}" w:val="{variable(value)}"/>' for name,value in values)+'</w:docVars>'
        assert settings.count('</w:settings>')==1
        parts['word/settings.xml']=settings.replace('</w:settings>',metadata+'</w:settings>').encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
