"""Generate the international paper-size matrix from the existing flowing report profile."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report
from build_fixture import paragraph, run

SIZES={'a4':(11906,16838),'a3':(16838,23811),'letter':(12240,15840),'legal':(12240,20160)}
KINDS=[f'{size}-{orientation}' for size in SIZES for orientation in ['portrait','landscape']]


def build(root, output, kind):
    size,orientation=kind.split('-'); width,height=SIZES[size]
    if orientation=='landscape':width,height=height,width
    entries=report(root,output,entries_only=True)
    source=entries['word/document.xml'].replace('<w:pgSz w:w="11906" w:h="16838"/>',f'<w:pgSz w:w="{width}" w:h="{height}" w:orient="{orientation}"/>')
    # Exercise asymmetric margins on one size without changing the fixed table widths.
    if size=='legal':
        source=source.replace('w:right="1134" w:bottom="1200" w:left="1134"','w:right="900" w:bottom="1400" w:left="1500"')
    localized=[('INT-RU','ru-RU','Отчёт о ходе проекта.'),('INT-UK','uk-UA','Київ, Україна. Сума 1 234,56 грн.'),('INT-EL','el-GR','Έκθεση έργου και στοιχεία.')]
    extra=paragraph(run(f'Papierformaat {size.upper()} {orientation}'))+''.join(paragraph(run(identifier+' '+text,f'<w:lang w:val="{language}"/>')) for identifier,language,text in localized)
    title_end=source.index('</w:p>')+len('</w:p>')
    source=source[:title_end]+extra+source[title_end:]
    entries['word/document.xml']=source
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as z:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            z.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('--kind',choices=KINDS);a=p.parse_args();root=Path(__file__).resolve().parents[3]
    if a.kind:build(root,a.output,a.kind)
    else:
        for kind in KINDS:build(root,a.output/kind/'source.docx',kind)
