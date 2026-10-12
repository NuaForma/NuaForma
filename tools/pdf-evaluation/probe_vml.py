"""Trusted synthetic-fixture experiments, never part of the production pipeline."""
import argparse
import copy
import hashlib
import json
import pathlib
import re
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=pathlib.Path)
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
source_hash = hashlib.sha256(args.source.read_bytes()).hexdigest()
args.directory.mkdir(parents=True, exist_ok=False)
variants = {
    'original': lambda s: s,
    'no-flow': lambda s: s.replace('mso-layout-flow-alt:bottom-to-top', ''),
    'swap-size': lambda s: s.replace('width:180pt;height:42pt', 'width:42pt;height:180pt'),
    'flow-only': lambda s: s.replace('rotation:90', 'rotation:0'),
    'swap-flow-only': lambda s: s.replace('width:180pt;height:42pt', 'width:42pt;height:180pt').replace('rotation:90', 'rotation:0'),
    'no-wrap': lambda s: s.replace('mso-layout-flow-alt:bottom-to-top', 'mso-layout-flow-alt:bottom-to-top;mso-wrap-style:none'),
    'swap-no-flow': lambda s: s.replace('width:180pt;height:42pt', 'width:42pt;height:180pt').replace('mso-layout-flow-alt:bottom-to-top', ''),
}
def vertical(s):
    return s.replace('width:180pt;height:42pt', 'width:42pt;height:180pt').replace('rotation:90', 'rotation:0').replace('type="#_x0000_t202"', 'type="#_x0000_t202" stroked="t" strokecolor="black" strokeweight="1pt"')

variants.update({
    'vertical-border': vertical,
    'vertical-offset': lambda s: vertical(s).replace('margin-left:-18pt', 'margin-left:-45pt'),
    'vertical-top': lambda s: vertical(s).replace('mso-layout-flow-alt:bottom-to-top', 'mso-layout-flow-alt:bottom-to-top;v-text-anchor:top'),
    'vertical-middle': lambda s: vertical(s).replace('mso-layout-flow-alt:bottom-to-top', 'mso-layout-flow-alt:bottom-to-top;v-text-anchor:middle'),
    'vertical-bottom': lambda s: vertical(s).replace('mso-layout-flow-alt:bottom-to-top', 'mso-layout-flow-alt:bottom-to-top;v-text-anchor:bottom'),
    'rect-original': lambda s: s.replace('<v:shape ', '<v:rect ').replace('</v:shape>', '</v:rect>'),
    'rect-vertical': lambda s: vertical(s).replace('<v:shape ', '<v:rect ').replace('</v:shape>', '</v:rect>'),
    'shape-type-rect': lambda s: s.replace('type="#_x0000_t202"', 'type="#_x0000_t1"'),
    'shape-no-type': lambda s: s.replace('type="#_x0000_t202"', ''),
    'rect-native': lambda s: variants['swap-flow-only'](s).replace('<v:shape ', '<v:rect ').replace('</v:shape>', '</v:rect>'),
    'rect-inset': lambda s: variants['rect-vertical'](s).replace('inset="0,0,0,0"', 'inset="0,0,0,1pt"'),
    'rect-thin': lambda s: variants['rect-vertical'](s).replace('strokeweight="1pt"', 'strokeweight="0.5pt"'),
    'rect-position': lambda s: variants['rect-vertical'](s).replace('mso-position-horizontal:right', 'mso-position-horizontal:absolute').replace('margin-left:-18pt', 'margin-left:540pt'),
})
def typed_rectangle(s):
    shape_type = '<v:shapetype xmlns:o="urn:schemas-microsoft-com:office:office" id="_nf_probe_rect" coordsize="21600,21600" o:spt="1" path="m0,0l21600,0,21600,21600,0,21600xe"><v:stroke joinstyle="miter"/><v:path o:connecttype="rect" textboxrect="0,0,21600,21600"/></v:shapetype>'
    return shape_type + s.replace('type="#_x0000_t202"', 'type="#_nf_probe_rect"')

variants.update({
    'typed-original': typed_rectangle,
    'typed-no-flow': lambda s: typed_rectangle(s).replace('mso-layout-flow-alt:bottom-to-top', ''),
    'typed-no-flow-270': lambda s: typed_rectangle(s).replace('mso-layout-flow-alt:bottom-to-top', '').replace('rotation:90', 'rotation:270'),
    'typed-vertical': lambda s: typed_rectangle(variants['swap-flow-only'](s)),
})
variants.update({
    'typed-anchor-top': lambda s: variants['typed-vertical'](s).replace('rotation:0', 'rotation:0;v-text-anchor:top'),
    'typed-anchor-bottom': lambda s: variants['typed-vertical'](s).replace('rotation:0', 'rotation:0;v-text-anchor:bottom'),
    'typed-anchor-middle': lambda s: variants['typed-vertical'](s).replace('rotation:0', 'rotation:0;v-text-anchor:middle'),
    'typed-margin': lambda s: variants['typed-vertical'](s).replace('mso-position-horizontal-relative:page', 'mso-position-horizontal-relative:margin'),
})
records = []
with zipfile.ZipFile(args.source) as source:
    document = source.read('word/document.xml').decode('utf-8')
    shapes = list(re.finditer(r'<v:shape\b[^>]*id="MarginBox".*?</v:shape>', document, re.S))
    assert len(shapes) == 1, 'Expected one synthetic margin shape'
    match = shapes[0]
    for name, transform in variants.items():
        target = args.directory / f'{name}.docx'
        replacement = transform(match.group())
        patched = document[:match.start()] + replacement + document[match.end():]
        with zipfile.ZipFile(target, 'x') as destination:
            for entry in source.infolist():
                destination.writestr(copy.copy(entry), patched.encode('utf-8') if entry.filename == 'word/document.xml' else source.read(entry))
        with zipfile.ZipFile(target) as result:
            changed = [entry.filename for entry in source.infolist() if source.read(entry) != result.read(entry.filename)]
            assert changed == ([] if name == 'original' else ['word/document.xml'])
        records.append(dict(variant=name, changedParts=changed, sourceSha256=source_hash))
assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_hash
(args.directory / 'probes.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print(json.dumps(records))
