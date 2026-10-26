"""Three separate synthetic table trials; no reference positions or forced page assignments."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report_entries
from build_fixture import paragraph, run


def text(text, bold=False, align='left', after=0, line=240, size=18):
    return paragraph(run(text, f'<w:sz w:val="{size}"/>' + ('<w:b/>' if bold else '')),
                     f'<w:spacing w:before="0" w:after="{after}" w:line="{line}" w:lineRule="exact"/><w:jc w:val="{align}"/>')


def cell(content, width, span=1, vertical=None, fill=None):
    props = f'<w:tcW w:w="{width}" w:type="dxa"/>'
    if span != 1: props += f'<w:gridSpan w:val="{span}"/>'
    if vertical: props += '<w:vMerge' + (' w:val="restart"' if vertical=='restart' else '') + '/>'
    if fill: props += f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>'
    return f'<w:tc><w:tcPr>{props}</w:tcPr>{content}</w:tc>'


def row(cells, header=False, split=False):
    return '<w:tr><w:trPr>' + ('' if split else '<w:cantSplit/>') + ('<w:tblHeader/>' if header else '') + '</w:trPr>' + ''.join(cells) + '</w:tr>'


def table(widths, rows, padding=(80,120,80,120)):
    props=f'<w:tblW w:w="{sum(widths)}" w:type="dxa"/><w:tblInd w:w="{padding[3]}" w:type="dxa"/><w:tblLayout w:type="fixed"/>'
    props+='<w:tblBorders>'+''.join(f'<w:{edge} w:val="single" w:sz="4" w:color="B8C5CC"/>' for edge in ['top','left','bottom','right','insideH','insideV'])+'</w:tblBorders>'
    props+='<w:tblCellMar>'+''.join(f'<w:{edge} w:w="{value}" w:type="dxa"/>' for edge,value in zip(['top','right','bottom','left'],padding))+'</w:tblCellMar>'
    return '<w:tbl><w:tblPr>'+props+'</w:tblPr><w:tblGrid>'+''.join(f'<w:gridCol w:w="{width}"/>' for width in widths)+'</w:tblGrid>'+''.join(rows)+'</w:tbl>'


def header(widths, labels):
    return row([cell(text(label,True),width,fill='DCEFF0') for width,label in zip(widths,labels)],header=True)


def merged():
    widths=[1200,900,5738,1800]
    rows=[row([cell(text('Indeling',True),2100,span=2,fill='DCEFF0'),cell(text('Uitvoering',True),7538,span=2,fill='DCEFF0')],header=True),
          header(widths,['Categorie','Code','Omschrijving','Bedrag'])]
    for i in range(1,33):
        start=(i-1)%8==0
        category=text(f'Fase {(i-1)//8+1}',True) if start else '<w:p/>'
        rows.append(row([cell(category,widths[0],vertical='restart' if start else 'continue',fill='EAF3F4'),
            cell(text(f'M{i:02d}'),widths[1]),
            cell(text('Controle van de documentgegevens en afstemming van de aandachtspunten met de betrokken medewerkers.'),widths[2]),
            cell(text(f'€ {100+i*12},00',align='right'),widths[3])]))
    return table(widths,rows)


def nested():
    widths=[1000,6838,1800]
    rows=[header(widths,['Code','Controle en toelichting','Status'])]
    for i in range(1,11):
        inner_widths=[1800,4798]
        inner=[header(inner_widths,['Onderdeel','Bevinding'])]
        for j,desc in enumerate(['Gegevens gecontroleerd en akkoord bevonden.','Aanvullende toelichting ontvangen en verwerkt.','De vastgelegde afspraken zijn bevestigd.'],1):
            inner.append(row([cell(text(f'N{i:02d}-{j}',size=16,line=200),1800),cell(text(desc,size=16,line=200),4798,fill='F3F7F8' if j%2==0 else None)]))
        content=text(f'Controle N{i:02d}',True,after=80)+table(inner_widths,inner,padding=(40,60,40,60))+text('',size=4,line=80)
        rows.append(row([cell(text(f'N{i:02d}'),1000),cell(content,6838),cell(text('Akkoord',align='right'),1800)]))
    return table(widths,rows)


def split_rows():
    widths=[1000,6838,1800]
    rows=[header(widths,['Code','Uitgevoerde controles','Uitkomst'])]
    def entry(code,count,split):
        content=''.join(text(f'{code}-{i:03d} Controle van de registratie en de vastgelegde afspraak.',line=240) for i in range(1,count+1))
        return row([cell(text(code,True),1000),cell(content,6838),cell(text('Beoordeeld',align='right'),1800)],split=split)
    rows += [entry('A01',2,False),entry('S01',76,True),entry('K01',28,False),entry('K02',76,False)]
    return table(widths,rows)


def build(root, output, kind):
    entries=report_entries(root,output,entries_only=True)
    original=entries['word/document.xml']
    prefix=original.split('<w:body>')[0]
    section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
    titles={'merged':'Overzicht met samengevoegde cellen','nested':'Controleoverzicht met detailtabellen','split':'Controleoverzicht met lange tabelrijen'}
    intros={'merged':'De groepskoppen delen meerdere kolommen. De categorieën lopen verticaal door over acht werkzaamheden en blijven ook bij een paginaovergang herkenbaar.',
            'nested':'Elke controle bevat een eigen detailtabel met bevindingen. De binnentabel heeft eigen celmarges en blijft binnen de omschrijving van de hoofdregel.',
            'split':'S01 mag over pagina’s doorlopen. K01 moet bij elkaar blijven. K02 is bewust hoger dan één volledige pagina en heeft eveneens de instelling om bij elkaar te blijven.'}
    blocks=paragraph(run(titles[kind]),'<w:pStyle w:val="Title"/>')+paragraph(run(intros[kind]))
    blocks+=dict(merged=merged,nested=nested,split=split_rows)[kind]()
    blocks+=paragraph(run('Einde van het controleoverzicht.'))
    entries['word/document.xml']=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)
    print('Advanced DOCX written:',kind,output)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--kind',choices=['merged','nested','split'],required=True)
    args=parser.parse_args()
    build(Path(__file__).resolve().parents[3],args.output,args.kind)
