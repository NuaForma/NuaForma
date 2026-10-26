"""Basic unpointed Hebrew: explicit paragraph direction, numbers, bands and tables; mixed strong directions reject."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_international_fixture import build as international
from build_fixture import paragraph, run


def localized(text, size=22, extra='', language='he-IL'):
    direction='<w:rtl/>' if any('\u0590'<=c<='\u05ff' for c in text) else '<w:rtl w:val="0"/>'
    complex_flags=('<w:bCs/>' if '<w:b/>' in extra else '<w:bCs w:val="0"/>')+('<w:iCs/>' if '<w:i/>' in extra else '<w:iCs w:val="0"/>')
    extra=extra.replace('<w:bCs/>','').replace('<w:iCs/>','')
    return run(text, direction+f'<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans" w:cs="Liberation Sans"/><w:sz w:val="{size}"/><w:szCs w:val="{size}"/><w:lang w:val="{language}"/>'+extra+complex_flags)


def build(root, output):
    # Reuse the existing numbering definition and the report's image relationships.
    international(root, output)
    with ZipFile(output) as z:
        entries = {name: z.read(name) for name in z.namelist()}
    entries = {name: value.decode('utf-8') if name.endswith(('.xml', '.rels')) else value for name, value in entries.items()}
    old = entries['word/document.xml']
    prefix = old.split('<w:body>')[0]
    section = '<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    rtl = '<w:bidi/><w:jc w:val="left"/>'
    body = [paragraph(localized('דוח התקדמות הפרויקט', 36, '<w:b/><w:bCs/>'), '<w:pStyle w:val="Title"/>'+rtl)]
    samples = [
        ('HEA', 'שלום עולם. המסמכים נבדקו והפרויקט מתקדם לפי התכנון.'),
        ('HEB', 'הדוח כולל מסמכים בעברית ובאנגלית לצורך בדיקת העיצוב. הצוות בדק את הנתונים ואישר את המשך העבודה. '+
         'הפרויקט מתקדם לפי התכנון והמסמכים נשמרים עם הכותרות והטבלאות המקוריות. פרטים נוספים מופיעים בעמוד הבא.'),
        ('HEC', 'מסמך עבור צוות העבודה בעלות 123.45 ובכמות 67 יחידות.'),
    ]
    for identifier, text in samples:
        body.append(paragraph(localized(text), rtl))
    body.append(paragraph(localized('הדגשה ', extra='<w:b/><w:bCs/>')+localized('ומילים באותיות נטויות ', extra='<w:i/><w:iCs/>')+localized('בתוך אותו משפט.'), rtl))
    body.append(paragraph(localized('טבלאות ורשימות', 36, '<w:b/><w:bCs/>'), '<w:pStyle w:val="Title"/><w:pageBreakBefore/>'+rtl))
    widths = [1400, 6600]
    edges = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right','insideH','insideV'])
    props = '<w:tblW w:w="8000" w:type="dxa"/><w:tblInd w:w="120" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'+edges+'</w:tblBorders><w:tblCellMar>'+''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ['top','left','bottom','right'])+'</w:tblCellMar>'
    rows = []
    for identifier, text in [('TA','המסמכים נבדקו ואושרו.'), ('TB','עלות 123.45 עבור העבודה וכמות 67 יחידות.')]:
        cells = []
        for value, width in zip([identifier,text], widths):
            cells.append(f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/></w:tcPr>'+paragraph(localized(value, 18), (rtl if any('\u0590'<=c<='\u05ff' for c in value) else '')+'<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/>')+'</w:tc>')
        rows.append('<w:tr><w:trPr><w:cantSplit/></w:trPr>'+''.join(cells)+'</w:tr>')
    body.append('<w:tbl><w:tblPr>'+props+'</w:tblPr><w:tblGrid>'+''.join(f'<w:gridCol w:w="{width}"/>' for width in widths)+'</w:tblGrid>'+''.join(rows)+'</w:tbl>')
    for identifier, text in [('LA','Review the documents.'),('LB','Preserve the layout and data.'),('LC','Confirm the next step.')]:
        body.append(paragraph(localized(identifier+' ', language='en-US')+localized(text, language='en-US'), '<w:bidi w:val="0"/><w:jc w:val="left"/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>'))
    entries['word/document.xml'] = prefix+'<w:body>'+''.join(body)+section+'</w:body></w:document>'
    # Keep the logo in its original cell; only its neighbouring text cell becomes RTL.
    previous = run('NuaForma  |  Voortgangsrapport','<w:b/><w:sz w:val="18"/>')
    entries['word/header1.xml'] = entries['word/header1.xml'].replace(previous, localized('דוח התקדמות',18,'<w:b/><w:bCs/>')).replace('<w:jc w:val="right"/>','<w:bidi/><w:jc w:val="left"/>')
    footer = localized('עמוד ',18)+'<w:fldSimple w:instr=" PAGE ">'+localized('1',18)+'</w:fldSimple>'
    entries['word/footer1.xml'] = prefix.replace('<w:document','<w:ftr')+paragraph(footer,'<w:pStyle w:val="Footer"/><w:bidi/>')+'</w:ftr>'
    with ZipFile(output,'w') as z:
        for name, value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.compress_type=ZIP_DEFLATED;info.create_system=0
            z.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args()
    build(Path(__file__).resolve().parents[3],a.output)
