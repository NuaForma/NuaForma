"""Build inert Word controls for tight automatic spacing and paragraph backgrounds."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from create_tab_collision_fixtures import parts as base_parts, W, R
from create_text_background_fixtures import run, shading


def paragraph(content, properties='', line=400, rule='exact', before=0, after=0):
    return (f'<w:p><w:pPr><w:spacing w:before="{before}" w:after="{after}" w:line="{line}" w:lineRule="{rule}"/>'
            '<w:widowControl w:val="0"/>' + properties + '</w:pPr>' + content + '</w:p>')


def tight():
    body=[]
    for index,line in enumerate([240,228,220,213,120]):
        content=''.join(run(f'T{index*4+j:02}_LINE',size=24,control='<w:br/>' if j<3 else '') for j in range(4))
        body.append(paragraph(content,line=line,rule='auto',before=80,after=120))
    body.append(paragraph(run('T20_SMALL',size=16)+run(' T21_LARGE',size=32,control='<w:br/>')+run('T22_NEXT',size=20),
                          line=220,rule='auto',before=100,after=100))
    body.append(paragraph(run('T23_INHERITED',control='<w:br/>')+run('T24_INHERITED'),'<w:pStyle w:val="Tight"/>',line=213,rule='auto'))
    body.append(paragraph(run('T25_RESET',control='<w:br/>')+run('T26_RESET'),'<w:pStyle w:val="Tight"/>',line=240,rule='auto'))
    return ''.join(body)


def pagination():
    return ''.join(paragraph(run(f'P{i:02}_LINE tight automatic paragraph spacing',size=24),line=220,rule='auto') for i in range(90))


def backgrounds():
    blue=shading('DDEEFF');yellow=shading('FFFF00')
    body=[paragraph(run('B00_BEFORE')),
          paragraph(run('B01_FILL'),blue,before=100,after=180),
          paragraph(run('B02_AFTER')),
          paragraph(run('B03_LEFT'),blue+'<w:ind w:left="720" w:right="360"/>'),
          paragraph(run('B04_HANG'),blue+'<w:ind w:left="720" w:hanging="360"/>'),
          paragraph(run('B05_WRAP '+'text wrapping '*18),yellow),
          paragraph(run('B06_GROUP'),blue,before=100,after=120),
          paragraph(run('B07_GROUP'),blue,before=180,after=160),
          paragraph(run('B08_END')),
          paragraph(run('B09_RUN',shading('FFFFFF')),blue),
          paragraph(run('B10_MARK', '<w:highlight w:val="green"/>'),blue),
          paragraph(run('B11_CLEAR'),shading('auto')),
          paragraph(run('B12_NIL'),'<w:shd w:val="nil"/>'),
          paragraph(run('B13_EMPTY_AFTER'))]
    return ''.join(body)


def inheritance():
    body=[]
    for i,prop in enumerate(['', '<w:shd w:val="nil"/>',shading('auto'),shading('FFFFFF')]):
        body.append(paragraph(run(f'I{i:02}_LIGHT'),'<w:pStyle w:val="Shaded"/>'+prop))
    for i,prop in enumerate(['', '<w:shd w:val="nil"/>',shading('auto'),shading('FFFFFF')]):
        body.append(paragraph(run(f'I{i+4:02}_DARK'),'<w:pStyle w:val="Dark"/>'+prop))
    body.append(paragraph(run('I08_AUTO')+run(' I09_BLACK','<w:color w:val="000000"/>')+
                          run(' I10_WHITE',shading('FFFFFF')), '<w:pStyle w:val="Dark"/>'))
    body.append(paragraph(run('I11_BEFORE'),'<w:pStyle w:val="Shaded"/>',before=100,after=120))
    body.append(paragraph(run('I12_DIFFERENT'),shading('FFE4B5'),before=180,after=100))
    body.append(paragraph(run('I13_PLAIN')))
    return ''.join(body)


def integration(cell_margin=80):
    border='<w:pBdr>'+''.join(f'<w:{edge} w:val="single" w:sz="8" w:color="000000" w:space="2"/>'
        for edge in ['top','left','bottom','right'])+'</w:pBdr>'
    body=paragraph(run('C00_BORDER'),shading('DDEEFF')+border)
    margins='<w:tblCellMar>'+''.join(f'<w:{edge} w:w="{cell_margin}" w:type="dxa"/>' for edge in ['top','left','bottom','right'])+'</w:tblCellMar>'
    body+='<w:tbl><w:tblPr><w:tblW w:w="7200" w:type="dxa"/><w:tblLayout w:type="fixed"/>'+margins+'</w:tblPr><w:tblGrid><w:gridCol w:w="3600"/><w:gridCol w:w="3600"/></w:tblGrid><w:tr>'
    for i in range(2):
        body+='<w:tc><w:tcPr><w:tcW w:w="3600" w:type="dxa"/>'+shading('008B90')+'</w:tcPr>'
        body+=paragraph(run(f'C{i*2+1:02}_CELL',size=24,control='<w:br/>')+run(f'C{i*2+2:02}_NEXT',size=24),
                        shading('FFFFFF') if i==0 else shading('FFFF00'),line=220,rule='auto')+'</w:tc>'
    body+='</w:tr></w:tbl>'
    body+=paragraph(run('C05_AFTER'),shading('DDEEFF'))
    return body


def split_background():
    content=''.join(run(f'S{i:02}_LINE',size=24,control='<w:br/>' if i<99 else '') for i in range(100))
    return paragraph(content,shading('DDEEFF'),line=220,rule='auto')


def joins():

    def border(color):
        return '<w:pBdr>'+''.join(f'<w:{edge} w:val="single" w:sz="8" w:color="{color}" w:space="2"/>'
            for edge in ['top','left','bottom','right'])+'</w:pBdr>'

    cases=[('', '<w:ind w:left="720"/>'), (border('000000'), ''),
           (border('000000'), border('FF0000')), (border('000000'), border('000000')),
           ('', '<w:ind w:right="720"/>'), ('', '<w:ind w:firstLine="360"/>'),
           ('<w:ind w:left="720" w:hanging="720"/>', '')]
    body=[]
    for i,(first,second) in enumerate(cases):
        body.append(paragraph(run(f'J{i*2:02}_FIRST'),shading('DDEEFF')+first,after=120))
        body.append(paragraph(run(f'J{i*2+1:02}_SECOND'),shading('FFE4B5')+second,before=180))
        body.append(paragraph(run(f'J{i+50:02}_SEPARATOR')))
    return ''.join(body)


def build(output):
    output.mkdir(parents=True,exist_ok=False)
    variants=[('tight',tight),('pagination',pagination),('backgrounds',backgrounds),('inheritance',inheritance)]
    variants += [('integration',integration),('zero-margins',lambda: integration(0)),('split-background',split_background),('joins',joins)]
    variants += [(f'ratio-{line}', lambda value=line: paragraph(run('R00_FIRST',size=24,control='<w:br/>')+
        run('R01_SECOND',size=24),line=value,rule='auto')) for line in [240,228,220,213,120]]
    for variant,content in variants:
        for paper in ['a4','letter']:
            source=base_parts(paper,'')
            section=source['word/document.xml'].split('<w:sectPr>')[1].split('</w:sectPr>')[0]
            source['word/styles.xml']=source['word/styles.xml'].replace('</w:styles>',
                '<w:style w:type="paragraph" w:styleId="Tight"><w:name w:val="Tight"/><w:basedOn w:val="Normal"/>'
                '<w:pPr><w:spacing w:line="213" w:lineRule="auto"/></w:pPr></w:style>' +
                ''.join(f'<w:style w:type="paragraph" w:styleId="{name}"><w:name w:val="{name}"/><w:basedOn w:val="Normal"/>'
                        '<w:pPr>'+shading(color)+'</w:pPr></w:style>' for name,color in [('Shaded','DDEEFF'),('Dark','000000')])+'</w:styles>')
            source['word/document.xml']=f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>'+content()+'<w:sectPr>'+section+'</w:sectPr></w:body></w:document>'
            if variant in ['integration','zero-margins']:
                section=section.replace('<w:pgSz','<w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz')
                section=section.replace('w:top="1134"','w:top="1800"').replace('w:bottom="1134"','w:bottom="1800" w:header="600" w:footer="600"')
                source['word/document.xml']=f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>'+content()+'<w:sectPr>'+section+'</w:sectPr></w:body></w:document>'
                for band,tag,index in [('header','hdr',90),('footer','ftr',91)]:
                    source[f'word/{band}1.xml']=f'<w:{tag} xmlns:w="{W}">'+paragraph(run(f'C{index}_BAND'),shading('DDEEFF'),line=240,rule='auto')+f'</w:{tag}>'
                    source['word/_rels/document.xml.rels']=source['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="{band}" Type="{R}/{band}" Target="{band}1.xml"/></Relationships>')
                    source['[Content_Types].xml']=source['[Content_Types].xml'].replace('</Types>',f'<Override PartName="/word/{band}1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{band}+xml"/></Types>')
            folder=output/variant/paper;folder.mkdir(parents=True)
            with ZipFile(folder/'source.docx','x') as archive:
                for name,data in sorted(source.items()):
                    info=ZipInfo(name,(2026,11,18,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
                    archive.writestr(info,data.encode())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    build(parser.parse_args().output)
