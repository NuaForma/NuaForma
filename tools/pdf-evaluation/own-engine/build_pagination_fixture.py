"""A4 paragraph-boundary cases with source keep rules, not renderer page assignments."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report_entries
from build_fixture import paragraph, run


def p(text, properties=''):
    return paragraph(run(text),'<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>'+properties)


def build(root, output):
    entries=report_entries(root,output,entries_only=True)
    old=entries['word/document.xml'];prefix=old.split('<w:body>')[0]
    section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    entries['word/styles.xml']=entries['word/styles.xml'].replace('</w:styles>',
        '<w:style w:type="paragraph" w:styleId="PaginationHeadingBase"><w:name w:val="Pagination Heading Base"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:keepLines/></w:pPr></w:style>'
        '<w:style w:type="paragraph" w:styleId="PaginationHeading"><w:name w:val="Pagination Heading"/><w:basedOn w:val="PaginationHeadingBase"/><w:pPr><w:spacing w:before="0" w:after="120" w:line="400" w:lineRule="exact"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style></w:styles>')
    content=[]
    cases=[('Koppen bij de volgende alinea',37,'G',''),('Regels van een alinea bijeenhouden',37,'K','<w:keepLines/>'),
        ('Geen losse eerste regel',38,'O','<w:widowControl/>'),('Geen losse laatste regel',36,'W','<w:widowControl/>'),
        ('Kop bij het begin van een lange alinea',36,'N','')]
    for case,(title,count,marker,flags) in enumerate(cases,1):
        content.append(paragraph(run(title),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
        content.append(paragraph(run(f'Proef {case} controleert de paginaovergang bij een beperkte resterende ruimte.'),
            '<w:spacing w:before="0" w:after="160" w:line="320" w:lineRule="exact"/>'))
        content.extend(p(f'F{case}-{i:02d} Deze controleregel vult het tekstgebied zonder een vaste pagina toe te wijzen.') for i in range(1,count+1))
        if case==1:
            content.extend(paragraph(run(text),'<w:pStyle w:val="PaginationHeading"/>') for text in ['Gekoppelde hoofdkop','Gekoppelde tussenkop'])
        if case==5:
            content.append(paragraph(run('Kop met doorlopende vervolgalinea'),'<w:pStyle w:val="PaginationHeading"/>'))
        text=' '.join(f'{marker}{i:02d}documentcontrole' for i in range(1,5))
        content.append(p(text,'<w:ind w:right="6600"/>'+flags))
        content.append(p(f'Einde van proef {case}. Alle vier controleregels moeten behouden blijven.'))
    entries['word/document.xml']=prefix+'<w:body>'+''.join(content)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);args=parser.parse_args()
    build(Path(__file__).resolve().parents[3],args.output)
