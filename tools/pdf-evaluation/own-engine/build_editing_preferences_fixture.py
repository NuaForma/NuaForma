"""Build passive editing-preference probes from reviewed package controls."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:
            parts={name:z.read(name) for name in z.namelist()}
        settings=parts['word/settings.xml'].decode()
        assert 'doNotAutoCompressPictures' not in settings and 'trackRevisions' not in settings
        preferences='<w:doNotAutoCompressPictures/><w:trackRevisions/>' if paper=='a4' else '<w:doNotAutoCompressPictures w:val="off"/><w:trackRevisions w:val="false"/>'
        parts['word/settings.xml']=settings.replace('</w:settings>',preferences+'</w:settings>').encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
