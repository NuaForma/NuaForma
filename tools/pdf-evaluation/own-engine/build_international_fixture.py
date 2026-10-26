"""Caller-font international text, mixed directions and script-specific source properties."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_report_fixture import build as report
from build_fixture import paragraph,run,W,R

SAMPLES=[
 ('RU01','ru-RU','Liberation Sans','Отчёт о ходе проекта. Документы проверены, следующий этап согласован.',False),
 ('UK01','uk-UA','Liberation Sans','Звіт про стан проєкту. Київ, Україна; сума 1 234,56 грн.',False),
 ('EL01','el-GR','Liberation Sans','Έκθεση προόδου. Η μορφοποίηση και τα στοιχεία του εγγράφου διατηρούνται.',False),
 ('HE01','he-IL','Noto Sans Hebrew','שלום עולם. המסמכים נבדקו והפרויקט מתקדם לפי התכנון.',True),
 ('AR01','ar-SA','Noto Sans Arabic','السَّلام عليكم 123',True),
 ('AR02','ar-SA','Noto Sans Arabic','تقرير متابعة المشروع. تمت مراجعة المستندات وتحديد الخطوة التالية.',True),
]

def build_rtl_probe(root,output):
    entries=report(root,output,entries_only=True);old=entries['word/document.xml'];prefix=old.split('<w:body>')[0];section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    content=[paragraph(run('Internationale documenttekst'),'<w:pStyle w:val="Title"/>'),paragraph(run('Deze proef controleert schriftsoorten, letterverbindingen en leesrichting met expliciet aangeleverde lettertypen. De tekst moet ook correct selecteerbaar en doorzoekbaar blijven.'))]
    for id,language,family,text,rtl in SAMPLES:
        if id=='HE01':content.append(paragraph(run('Hebreeuws en Arabisch'),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
        label=run(id+' ',f'<w:lang w:val="en-US"/><w:rtl w:val="0"/>')
        value=run(text,f'<w:sz w:val="28"/><w:lang w:val="{language}" w:bidi="{language}"/>'+('<w:rtl/>' if rtl else ''))
        value=value.replace('<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>',f'<w:rFonts w:ascii="{family}" w:hAnsi="{family}" w:cs="{family}"/>')
        props='<w:spacing w:before="0" w:after="200" w:line="640" w:lineRule="exact"/>'+(('<w:bidi/><w:jc w:val="right"/>') if rtl else '')
        content.append(paragraph(label+value,props))
    entries['word/document.xml']=prefix+'<w:body>'+''.join(content)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as z:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.compress_type=ZIP_DEFLATED;info.create_system=0;z.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

def build(root,output):
    entries=report(root,output,entries_only=True);old=entries['word/document.xml'];prefix=old.split('<w:body>')[0];section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    def localized(text,language,extra=''):
        return run(text,f'<w:lang w:val="{language}"/>'+extra)
    content=[paragraph(run('Cyrillische en Griekse documenttekst'),'<w:pStyle w:val="Title"/>'),paragraph(run('Deze proef controleert Russische, Oekraïense en Griekse tekst in alinea’s, tabellen en genummerde lijsten. De bron vermeldt de taal expliciet; tekst en huisstijl moeten behouden blijven.'))]
    for id,language,family,text,rtl in SAMPLES[:3]:
        content.append(paragraph(localized(id+' ', 'en-US','<w:b/>')+localized(text,language)))
    content.append(paragraph(localized('M01 NF-2026-0057 | Київ | Αθήνα | € 1.234,56','uk-UA','<w:i/>')))
    content.append(paragraph(run('Tabellen en nummering'),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
    widths=[1400,6600];edges=''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right','insideH','insideV'])
    props='<w:tblW w:w="8000" w:type="dxa"/><w:tblInd w:w="120" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'+edges+'</w:tblBorders><w:tblCellMar>'+''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ['top','left','bottom','right'])+'</w:tblCellMar>'
    rows=[]
    for n,(id,language,family,text,rtl) in enumerate(SAMPLES[:3],1):
        cells=[]
        for value,width in zip([f'T{n:02d}',text],widths):
            cells.append(f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/></w:tcPr>'+paragraph(localized(value,language,'<w:sz w:val="18"/>'),'<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/>')+'</w:tc>')
        rows.append('<w:tr><w:trPr><w:cantSplit/></w:trPr>'+''.join(cells)+'</w:tr>')
    content.append('<w:tbl><w:tblPr>'+props+'</w:tblPr><w:tblGrid>'+''.join(f'<w:gridCol w:w="{width}"/>' for width in widths)+'</w:tblGrid>'+''.join(rows)+'</w:tbl>')
    for n,(language,text) in enumerate([('ru-RU','L01 Проверить документы и подтвердить следующий этап.'),('uk-UA','L02 Перевірити дані та зберегти оформлення.'),('el-GR','L03 Έλεγχος των στοιχείων και της μορφοποίησης.')],1):
        content.append(paragraph(localized(text,language),'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>'))
    entries['word/numbering.xml']=f'<w:numbering xmlns:w="{W}"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:suff w:val="tab"/><w:lvlJc w:val="left"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="720"/></w:tabs><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>'
    entries['word/settings.xml']=f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/></w:settings>'
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="numbering" Type="{R}/numbering" Target="numbering.xml"/><Relationship Id="settings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    entries['word/document.xml']=prefix+'<w:body>'+''.join(content)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as z:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.compress_type=ZIP_DEFLATED;info.create_system=0;z.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('--rtl-probe',action='store_true',help='Build the unqualified shaping/RTL research source instead of the qualified LTR-script proof');a=p.parse_args();(build_rtl_probe if a.rtl_probe else build)(Path(__file__).resolve().parents[3],a.output)
