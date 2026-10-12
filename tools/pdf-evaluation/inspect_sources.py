"""Inspect only synthetic DOCX fixture font names; never render or mutate input."""
import pathlib
import sys
import xml.etree.ElementTree as ET
import zipfile

for filename in sys.argv[1:]:
    fonts = set()
    with zipfile.ZipFile(filename) as package:
        for name in package.namelist():
            if name.startswith('word/') and name.endswith('.xml'):
                for node in ET.fromstring(package.read(name)).iter():
                    for key, value in node.attrib.items():
                        if key.split('}')[-1] in ('ascii', 'hAnsi', 'eastAsia', 'cs'):
                            fonts.add(value)
    print(pathlib.Path(filename).name, sorted(fonts))
