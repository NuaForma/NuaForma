"""Build direct and inherited Word theme-fill controls without replacing references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re


def paragraph(text):
    return ('<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/>'
            '<w:widowControl w:val="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr><w:t>'+text+'</w:t></w:r></w:p>')


def build(base, output):
    values = [('background1','themeFillShade','D9','D9D9D9'),('background1','themeFillShade','A6','A6A6A6'),
              ('background1','themeFillShade','F2','F2F2F2'),('text2','themeFillTint','66','8DB3E2'),
              ('accent1',None,None,'008B90'),('none',None,None,'auto')]
    for paper in ['a4','letter']:
        with ZipFile(base/paper/'source.docx') as archive:
            original = {name:archive.read(name) for name in archive.namelist()}
        for variant in ['direct','style']:
            parts = original.copy()
            theme,count = re.subn(r'(<a:dk2>\s*<a:srgbClr\s+val=)"333333"',r'\1"1F497D"',parts['word/theme/theme1.xml'].decode())
            assert count == 1
            parts['word/theme/theme1.xml'] = theme.encode()
            document = parts['word/document.xml'].decode()
            prefix = document.split('<w:body>')[0]+'<w:body>'
            suffix = '<w:sectPr>'+document.split('<w:sectPr>')[1]
            style = '<w:tblStyle w:val="CellFillStyle"/>' if variant == 'style' else ''
            borders = ''.join('<w:'+side+' w:val="single" w:sz="4" w:color="222222"/>' for side in ['top','left','bottom','right','insideH','insideV'])
            margins = ''.join('<w:'+side+' w:w="'+str(value)+'" w:type="dxa"/>' for side,value in [('top',80),('left',100),('bottom',80),('right',100)])
            body = paragraph('THEME CELL FILLS '+paper.upper())
            body += '<w:tbl><w:tblPr>'+style+'<w:tblW w:w="6000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'+borders+'</w:tblBorders><w:tblCellMar>'+margins+'</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>'
            inherited = '<w:shd w:val="clear" w:color="auto" w:fill="FF0000" w:themeFill="background1" w:themeFillShade="D9"/>'
            for index,(role,transform,value,expected) in enumerate(values):
                body += '<w:tr><w:trPr><w:cantSplit/></w:trPr>'
                for column in ['T','C']:
                    fill = ('336699' if role == 'none' else 'FF0000') if column == 'T' else expected
                    attributes = (' w:themeFill="'+role+'"'+(' w:'+transform+'="'+value+'"' if transform else '')) if column == 'T' else ''
                    shading = '<w:shd w:val="clear" w:color="auto" w:fill="'+fill+'"'+attributes+'/>'
                    if variant == 'style' and index == 0 and column == 'T':
                        shading = ''
                    body += '<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/>'+shading+'</w:tcPr>'+paragraph('C'+str(index).zfill(2)+column)+'</w:tc>'
                body += '</w:tr>'
            body += '</w:tbl>'+paragraph('END_THEME_FILLS')
            parts['word/document.xml'] = (prefix+body+suffix).encode()
            if variant == 'style':
                definition = '<w:style w:type="table" w:styleId="CellFillStyle"><w:name w:val="Qualified Cell Fill"/><w:tcPr>'+inherited+'</w:tcPr></w:style>'
                for name in ['word/styles.xml','word/stylesWithEffects.xml']:
                    if name in parts:
                        parts[name] = parts[name].decode().replace('</w:styles>',definition+'</w:styles>').encode()
            folder = output/variant/paper
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
