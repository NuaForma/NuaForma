"""Build visible Unicode-space probes and an explicit-preservation control."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape
import argparse,xml.etree.ElementTree as E


def run(text, preserve, bold=False, color='000000', force=False):
    props='<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/>'+('<w:b/>' if bold else '')+f'<w:color w:val="{color}"/></w:rPr>'
    attribute=' xml:space="preserve"' if preserve or force else ''
    return '<w:r>'+props+'<w:t'+attribute+'>'+escape(text)+'</w:t></w:r>'


def paragraph(content,right=0):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="140" w:line="240" w:lineRule="auto"/>'+(f'<w:ind w:right="{right}"/>' if right else '')+'</w:pPr>'+content+'</w:p>'


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:original={n:z.read(n) for n in z.namelist()}
        document=original['word/document.xml'].decode();prefix=document[:document.index('<w:body>')+len('<w:body>')];suffix=document[document.index('<w:sectPr'):]
        ns='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
        section=E.fromstring(document).find(ns+'body/'+ns+'sectPr');page=section.find(ns+'pgSz');margins=section.find(ns+'pgMar')
        narrow_right=int(page.get(ns+'w'))-int(margins.get(ns+'left'))-int(margins.get(ns+'right'))-3600
        for mode in ['preserve','implicit']:
            explicit=mode=='preserve'
            body=paragraph(run('UNICODE SPACE QUALIFICATION '+paper.upper(),True,True))
            for index,space in enumerate(['\u00a0','\u2007','\u202f'],1):
                for position,text in [('LEAD',space+'B'),('TRAIL','A'+space),('ONLY',space+space)]:
                    label=f'S{index}_{position}'
                    body+=paragraph(run(label+'_L',True,color='174E86')+run(text,explicit)+run(label+'_R',True,color='985000'))
            body+=paragraph(run('ASCII_L',True)+run(' A ',True,force=True)+run('ASCII_R',True))
            body+=paragraph(run('WRAP alpha beta gamma Delta\u00a0Pair omega',explicit),narrow_right)
            body+=paragraph(run('WRAP alpha beta gamma Delta Pair omega',True),narrow_right)
            parts=dict(original);parts['word/document.xml']=(prefix+body+suffix).encode()
            target=output/mode/paper;target.mkdir(parents=True,exist_ok=False)
            with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
                for name,data in parts.items():
                    info=ZipInfo(name,(2025,1,1,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();build(args.base,args.output)
