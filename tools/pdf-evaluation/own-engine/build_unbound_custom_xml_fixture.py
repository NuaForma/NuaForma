"""Build inert unbound custom-XML probes without vendor-specific metadata payloads."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
P='http://schemas.openxmlformats.org/package/2006/relationships'
D='http://schemas.openxmlformats.org/officeDocument/2006/customXml'


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as z:
            parts={name:z.read(name) for name in z.namelist()}
        folder='customXml' if paper=='a4' else 'customXML'
        for index in [90,91]:
            namespace='urn:nuaforma:fixture:stored-data:'+str(index)
            if index==90:
                data=f'<m:metadata xmlns:m="{namespace}"><m:title>{{NOT_A_TEMPLATE</m:title><m:language>not a font or a layout directive</m:language></m:metadata>'
            else:
                data=f'<m:events xmlns:m="{namespace}"><m:handler class="NotExecutable"><m:script>{{UNFINISHED_DATA_ONLY</m:script></m:handler></m:events>'
            name=f'{folder}/item{index}.xml';assert name not in parts;parts[name]=data.encode()
            identity='{00000000-0000-0000-0000-'+str(index).zfill(12)+'}'
            parts[f'{folder}/itemProps{index}.xml']=(f'<ds:datastoreItem xmlns:ds="{D}" ds:itemID="{identity}"><ds:schemaRefs><ds:schemaRef ds:uri="{namespace}"/></ds:schemaRefs></ds:datastoreItem>').encode()
            parts[f'{folder}/_rels/item{index}.xml.rels']=(f'<Relationships xmlns="{P}"><Relationship Id="properties{index}" Type="{R}/customXmlProps" Target="itemProps{index}.xml"/></Relationships>').encode()
            main=parts['word/_rels/document.xml.rels'].decode();main=main.replace('</Relationships>',f'<Relationship Id="stored{index}" Type="{R}/customXml" Target="../{name}"/></Relationships>');parts['word/_rels/document.xml.rels']=main.encode()
            types=parts['[Content_Types].xml'].decode();types=types.replace('</Types>',f'<Override PartName="/{name}" ContentType="application/xml"/><Override PartName="/{folder}/itemProps{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.customXmlProperties+xml"/></Types>');parts['[Content_Types].xml']=types.encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();build(args.base,args.output)
