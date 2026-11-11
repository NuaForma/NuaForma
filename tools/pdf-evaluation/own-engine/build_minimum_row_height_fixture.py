"""Create Word minimum-row-height fixtures on A4 and Letter without replacing references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def paragraph(text, page_break=False):
    page = '<w:pageBreakBefore/>' if page_break else ''
    return ('<w:p><w:pPr>'+page+'<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/>'
            '<w:widowControl w:val="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr><w:t>'+text+'</w:t></w:r></w:p>')


def row(label, minimum, mode, alignment='top', header=False, lines=1):
    height = '<w:trHeight w:val="0" w:hRule="auto"/>' if minimum is None else (
        '<w:trHeight w:val="'+str(minimum)+'"'+(' w:hRule="atLeast"' if mode=='explicit' else '')+'/>')
    properties = '<w:trPr><w:cantSplit/>'+('<w:tblHeader/>' if header else '')+height+'</w:trPr>'
    cells = ''
    for column in ['A','B']:
        align = 'top' if column=='A' else alignment
        content = paragraph(label+column)
        for index in range(1,lines):
            content += paragraph('extra line '+str(index))
        cells += ('<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/><w:vAlign w:val="'+align+'"/></w:tcPr>'+content+'</w:tc>')
    return '<w:tr>'+properties+cells+'</w:tr>'


def table(rows):
    borders = ''.join('<w:'+side+' w:val="single" w:sz="4" w:color="222222"/>' for side in ['top','left','bottom','right','insideH','insideV'])
    margins = ''.join('<w:'+side+' w:w="'+str(value)+'" w:type="dxa"/>' for side,value in [('top',40),('left',100),('bottom',60),('right',100)])
    return ('<w:tbl><w:tblPr><w:tblW w:w="6000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblInd w:w="0" w:type="dxa"/>'
            '<w:tblBorders>'+borders+'</w:tblBorders><w:tblCellMar>'+margins+'</w:tblCellMar></w:tblPr>'
            '<w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>'+rows+'</w:tbl>')


def build(base, output):
    for mode in ['explicit','implicit']:
        for paper in ['a4','letter']:
            with ZipFile(base/paper/'source.docx') as archive:
                parts = {name:archive.read(name) for name in archive.namelist()}
            original = parts['word/document.xml'].decode()
            prefix = original.split('<w:body>')[0]+'<w:body>'
            suffix = '<w:sectPr>'+original.split('<w:sectPr>')[1]
            body = paragraph('MINIMUM ROW HEIGHTS '+paper.upper())
            rows = row('R00',None,mode,header=True)
            for label,height,align,lines in [('R01',200,'top',1),('R02',800,'center',1),('R03',1200,'bottom',1),('R04',800,'center',2),('R05',0,'top',1),('R06',200,'bottom',3)]:
                rows += row(label,height,mode,align,lines=lines)
            body += table(rows)+paragraph('END_MINIMUM_ROWS')
            body += paragraph('REPEATED MINIMUM ROWS',page_break=True)
            rows = row('H00',400,mode,header=True)
            for index in range(1,10):
                rows += row('F'+str(index).zfill(2),1600,mode,'center' if index%2 else 'bottom')
            body += table(rows)+paragraph('END_REPEATED_ROWS')
            parts['word/document.xml'] = (prefix+body+suffix).encode()
            folder = output/mode/paper
            folder.mkdir(parents=True,exist_ok=False)
            with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
                for name,data in parts.items():
                    entry = ZipInfo(name,(2025,1,1,0,0,0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry,data)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    build(args.base,args.output)
