"""Prepare controlled MC/WPS text-frame probes; standard library and existing fixture helpers."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from argparse import ArgumentParser
from build_frame_fixtures import drawing_frame,frame,p


def build():
    parser=ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    out=args.output;out.mkdir(parents=True,exist_ok=False)
    for name in ['a4','letter']:
     with ZipFile(args.base/name/'source.docx') as z:parts={n:z.read(n) for n in z.namelist()}
     content=p('MC01 selected text frame.',size=20,line=280)
     choice=drawing_frame('text90',72,480,240,80,content).removeprefix('<w:r>').removesuffix('</w:r>')
     fallback=frame('mc-frame',72,480,240,80,content,fill=None).removeprefix('<w:r>').removesuffix('</w:r>')
     namespaces='xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w10="urn:schemas-microsoft-com:office:word"'
     doc=parts['word/document.xml'].decode().replace('<w:document ', '<w:document '+namespaces+' ')
     anchor='<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="360" w:lineRule="exact"/></w:pPr><w:r><mc:AlternateContent><mc:Choice Requires="wps">'+choice+'</mc:Choice><mc:Fallback>'+fallback+'</mc:Fallback></mc:AlternateContent></w:r></w:p>'
     assert doc.count('<w:sectPr>')==1
     parts['word/document.xml']=doc.replace('<w:sectPr>',anchor+'<w:sectPr>').encode()
     target=out/name;target.mkdir()
     with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
      for n,data in parts.items():
       i=ZipInfo(n,(2025,1,1,0,0,0));i.compress_type=ZIP_DEFLATED;z.writestr(i,data)


if __name__ == "__main__":
    build()
