"""Regression checks for research copy isolation, not renderer fidelity tests."""
import pathlib
import tempfile
import unittest
import zipfile

from conversion_copy import conversion_copy, normalize

SHAPE = '<v:shape style="width:180pt;height:42pt;rotation:90"><v:textbox style="mso-layout-flow-alt:bottom-to-top"><w:txbxContent><w:p><w:r><w:rPr><w:rFonts w:ascii="Private Font"/></w:rPr><w:t>*NF001*</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape>'


class ConversionCopyTest(unittest.TestCase):
    def test_style_only_and_idempotence(self):
        expected = SHAPE.replace('width:180pt;height:42pt;rotation:90', 'width:42pt;height:180pt;rotation:0')
        result, count = normalize('prefix' + SHAPE + 'suffix')
        self.assertEqual(('prefix' + expected + 'suffix', 1), (result, count))
        self.assertEqual((result, 0), normalize(result))

    def test_nonmatching_objects_unchanged(self):
        for xml in (SHAPE.replace('rotation:90', 'rotation:315'),
                    SHAPE.replace('bottom-to-top', 'top-to-bottom'),
                    '<v:shape style="rotation:90"><v:textpath string="CONCEPT"/></v:shape>'):
            self.assertEqual((xml, 0), normalize(xml))

    def test_unknown_dimension_rejected(self):
        for value in ('20%', '0pt', '-1pt'):
            with self.assertRaises(ValueError):
                normalize(SHAPE.replace('180pt', value))

    def test_source_and_other_package_parts_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            source, destination = directory / 'source.docx', directory / 'copy.docx'
            parts = {'word/document.xml': SHAPE.encode(), 'word/header1.xml': b'<header/>',
                     'word/fonts/private.odttf': bytes(range(256)), 'word/_rels/fontTable.xml.rels': b'<relationships/>'}
            with zipfile.ZipFile(source, 'x') as package:
                for name, value in parts.items():
                    package.writestr(name, value)
            original = source.read_bytes()
            result = conversion_copy(source, destination)
            self.assertEqual(original, source.read_bytes())
            self.assertEqual(['word/document.xml'], result['changedParts'])
            with zipfile.ZipFile(destination) as package:
                for name, value in parts.items():
                    if name != 'word/document.xml':
                        self.assertEqual(value, package.read(name))
            with self.assertRaises(FileExistsError):
                conversion_copy(source, destination)
            self.assertEqual(original, source.read_bytes())


if __name__ == '__main__':
    unittest.main()
