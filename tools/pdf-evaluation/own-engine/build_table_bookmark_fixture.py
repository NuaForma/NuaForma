"""Build fixed table probes with bookmark closures in paragraphs or ordered table positions."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape
import argparse


def run(text,bold=False):
    return '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/>'+('<w:b/>' if bold else '')+'</w:rPr><w:t>'+escape(text)+'</w:t></w:r>'


def start(index,name):
    return f'<w:bookmarkStart w:id="{index}" w:name="{name}"/>'


def end(index):
    return f'<w:bookmarkEnd w:id="{index}"/>'


def paragraph(text,opening='',closing='',bold=False):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>'+opening+run(text,bold)+closing+'</w:p>'


def cell(text,width,opening='',closing=''):
    return f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/><w:vAlign w:val="top"/></w:tcPr>'+paragraph(text,opening,closing)+'</w:tc>'


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:original={n:z.read(n) for n in z.namelist()}
        document=original['word/document.xml'].decode();prefix=document[:document.index('<w:body>')+len('<w:body>')];suffix=document[document.index('<w:sectPr'):]
        for mode in ['control','interleaved']:
            control=mode=='control'
            body=paragraph('TABLE BOOKMARK ENDINGS '+paper.upper(),bold=True)
            body+=paragraph('BEFORE_TABLE',start(21,'_BeforeTable'),end(21) if control else '')
            table='<w:tbl><w:tblPr><w:tblW w:w="6200" w:type="dxa"/><w:tblInd w:w="0" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'
            table+=''.join(f'<w:{side} w:val="single" w:sz="4" w:color="222222"/>' for side in ['top','left','bottom','right','insideH','insideV'])
            table+='</w:tblBorders><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="2600"/><w:gridCol w:w="3600"/></w:tblGrid>'
            if not control:table+=end(21)
            table+='<w:tr>'+cell('CELL_11',2600,start(22,'_FirstRow'),end(22) if control else '')+cell('CELL_12',3600)+'</w:tr>'
            if not control:table+=end(22)
            table+='<w:tr>'+cell('CELL_21',2600)+cell('CELL_22',3600,start(23,'_LastRow'),end(23) if control else '')+'</w:tr>'
            if not control:table+=end(23)
            body+=table+'</w:tbl>'+paragraph('AFTER_TABLE')
            parts=dict(original);parts['word/document.xml']=(prefix+body+suffix).encode()
            target=output/mode/paper;target.mkdir(parents=True,exist_ok=False)
            with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
                for name,data in parts.items():
                    info=ZipInfo(name,(2025,1,1,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();build(args.base,args.output)
