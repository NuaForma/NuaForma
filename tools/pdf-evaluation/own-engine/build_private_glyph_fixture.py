"""Build assigned/private-use aliases of identical physical glyphs without altering any font."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape

FAMILY='Font Awesome 6 Free Solid'
ICONS=[('Envelope',0xF003,0x2709,'003366'),('Heart',0xF004,0x2665,'AA0066'),('Check',0xF00C,0x2713,'008040'),('Cross',0xF00D,0x00D7,'CC3311')]


def run(text,family='Liberation Sans',size=22,color='000000',bold=False):
    return '<w:r><w:rPr><w:rFonts w:ascii="'+family+'" w:hAnsi="'+family+'"/><w:sz w:val="'+str(size)+'"/>'+('<w:b/>' if bold else '')+'<w:color w:val="'+color+'"/></w:rPr><w:t xml:space="preserve">'+escape(text)+'</w:t></w:r>'


def paragraph(content):
    return '<w:p><w:pPr><w:spacing w:before="120" w:after="60" w:line="480" w:lineRule="exact"/></w:pPr>'+content+'</w:p>'


def build(base,output):
    for mode in ['control','private']:
        for paper in ['a4','letter']:
            with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:parts={name:z.read(name) for name in z.namelist()}
            old=parts['word/document.xml'].decode();prefix=old.split('<w:body>')[0];section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
            body=paragraph(run('R00 Symbol glyphs '+paper.upper(),size=28,bold=True))
            for index,(label,private,assigned,color) in enumerate(ICONS,1):
                point=private if mode=='private' else assigned
                body+=paragraph(run(f'R0{index} {label}: ')+run(chr(point),FAMILY,32,color)+run(' same physical glyph'))
            body+=paragraph(run('R05 Literal words stay searchable.'))
            parts['word/document.xml']=(prefix+'<w:body>'+body+section+'</w:body></w:document>').encode()
            target=output/mode/paper;target.mkdir(parents=True,exist_ok=False)
            with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
                for name,data in parts.items():
                    item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
