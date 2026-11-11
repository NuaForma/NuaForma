"""Build independent plain-color table-indentation controls without replacing references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re


def paragraph(text):
    return ('<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/>'
            '<w:widowControl w:val="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr><w:t>'+text+'</w:t></w:r></w:p>')


def compatibility(settings, mode):
    if mode == 'absent':
        return settings
    setting = '<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="'+mode+'"/>'
    if '</w:compat>' in settings:
        return settings.replace('</w:compat>',setting+'</w:compat>')
    if re.search(r'<w:compat\s*/>',settings):
        return re.sub(r'<w:compat\s*/>','<w:compat>'+setting+'</w:compat>',settings)
    return settings.replace('</w:settings>','<w:compat>'+setting+'</w:compat></w:settings>')


def build(base, output):
    for paper in ['a4','letter']:
        with ZipFile(base/paper/'source.docx') as archive:
            original = {name:archive.read(name) for name in archive.namelist()}
        document = original['word/document.xml'].decode()
        prefix = document.split('<w:body>')[0]+'<w:body>'
        suffix = '<w:sectPr>'+document.split('<w:sectPr>')[1]
        for mode in ['absent','12','14','15']:
            for kind in ['implicit','explicit']:
                parts = original.copy()
                indent = '<w:tblInd w:w="0" w:type="dxa"/>' if kind == 'explicit' else ''
                borders = ''.join('<w:'+side+' w:val="single" w:sz="4" w:color="222222"/>' for side in ['top','left','bottom','right','insideH','insideV'])
                margins = ''.join('<w:'+side+' w:w="'+str(value)+'" w:type="dxa"/>' for side,value in [('top',80),('left',100),('bottom',80),('right',100)])
                body = paragraph('TABLE INDENTATION '+paper.upper())
                body += '<w:tbl><w:tblPr><w:tblW w:w="6000" w:type="dxa"/><w:tblLayout w:type="fixed"/>'+indent+'<w:tblBorders>'+borders+'</w:tblBorders><w:tblCellMar>'+margins+'</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>'
                for row,fill in enumerate(['D9D9D9','A6A6A6','FFFFFF']):
                    body += '<w:tr><w:trPr><w:cantSplit/></w:trPr>'
                    for column in ['A','B']:
                        body += '<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/><w:shd w:val="clear" w:color="auto" w:fill="'+fill+'"/></w:tcPr>'+paragraph('I'+str(row).zfill(2)+column)+'</w:tc>'
                    body += '</w:tr>'
                body += '</w:tbl>'+paragraph('END_TABLE_INDENTATION')
                parts['word/document.xml'] = (prefix+body+suffix).encode()
                parts['word/settings.xml'] = compatibility(parts['word/settings.xml'].decode(),mode).encode()
                folder = output/(mode+'-'+kind)/paper
                folder.mkdir(parents=True,exist_ok=False)
                with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
                    for name,data in parts.items():
                        entry = ZipInfo(name,(2025,1,1,0,0,0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry,data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    build(args.base,args.output)
