"""Copy isolation and bounded geometry-profile checks, not visual acceptance."""
import pathlib
import tempfile
import unittest
import zipfile
from drawingml_copy import conversion_copy, normalize

TEXT = '<w:txbxContent><w:p><w:r><w:rPr><w:rFonts w:ascii="Private Font"/></w:rPr><w:t>*NF001*</w:t></w:r></w:p></w:txbxContent>'
STYLE = 'position:absolute;width:180pt;height:42pt;rotation:90;margin-left:-18pt;margin-top:-115pt;mso-position-horizontal:right;mso-position-horizontal-relative:page;mso-position-vertical:bottom;mso-position-vertical-relative:margin'
SHAPE = f'<w:pict><v:shape id="MarginBox" type="#_x0000_t202" style="{STYLE}"><v:textbox inset="0,0,0,0" style="mso-layout-flow-alt:bottom-to-top">{TEXT}</v:textbox></v:shape></w:pict>'
SECTION = '<w:sectPr><w:pgMar w:right="1440"/></w:sectPr>'


class DrawingmlCopyTest(unittest.TestCase):
    def test_native_runs_anchor_and_dynamic_dimensions(self):
        for width, height in ((180, 42), (220, 60), (120, 28)):
            source = SHAPE.replace('180pt', f'{width}pt').replace('42pt', f'{height}pt') + SECTION
            result, count = normalize(source)
            self.assertEqual(1, count)
            self.assertIn(TEXT, result)
            self.assertIn(f'<wp:extent cx="{height * 12700}" cy="{width * 12700}"/>', result)
            self.assertIn('relativeFrom="rightMargin"><wp:align>left', result)
            self.assertNotIn('540', result)
            self.assertEqual((result, 0), normalize(result))

    def test_ids_and_other_objects(self):
        prefix = '<wp:docPr id="77"/><v:shape><v:textpath string="CONCEPT"/></v:shape>'
        result, count = normalize(prefix + SHAPE + SHAPE + SECTION)
        self.assertEqual(2, count)
        self.assertTrue(result.startswith(prefix))
        self.assertIn('<wp:docPr id="78"', result)
        self.assertIn('<wp:docPr id="79"', result)

    def test_optional_inline_keeps_native_content_and_insets(self):
        source = f'<w:pict><v:shape id="TextBox1" type="#_x0000_t202" style="width:260pt;height:35pt"><v:textbox inset="6pt,3pt,6pt,3pt">{TEXT}</v:textbox></v:shape></w:pict>' + SECTION
        self.assertEqual((source, 0), normalize(source))
        result, count = normalize(source, include_inline=True)
        self.assertEqual(1, count)
        self.assertIn('<wp:inline', result)
        self.assertIn(TEXT, result)
        self.assertIn('lIns="76200" tIns="38100" rIns="76200" bIns="38100"', result)

    def test_unmeasured_matching_profiles_rejected(self):
        for source in (SHAPE.replace('height:42pt', 'height:100pt') + SECTION,
                       SHAPE.replace('width:180pt', 'width:20%') + SECTION,
                       SHAPE.replace('right;', 'center;') + SECTION,
                       SHAPE.replace('inset="0,0,0,0"', 'inset="1pt,0,0,0"') + SECTION,
                       SHAPE.replace('bottom-to-top', 'bottom-to-top;v-text-anchor:middle') + SECTION,
                       SHAPE.replace('type="#_x0000_t202"', 'type="#_x0000_t202" stroked="f"') + SECTION,
                       SHAPE + SECTION.replace('1440', '480'),
                       SHAPE):
            with self.assertRaises(ValueError):
                normalize(source)

    def test_copy_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / 'source.docx'
            target = pathlib.Path(directory) / 'copy.docx'
            xml = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml">' + SHAPE + SECTION + '</w:document>'
            parts = {'word/document.xml': xml.encode(),
                     'word/styles.xml': b'explicit-styles',
                     'word/header1.xml': b'watermark', 'word/fonts/font.odttf': bytes(range(256)),
                     'word/_rels/fontTable.xml.rels': b'relationships'}
            with zipfile.ZipFile(source, 'x') as package:
                for name, value in parts.items():
                    package.writestr(name, value)
            before = source.read_bytes()
            self.assertEqual(['word/document.xml'], conversion_copy(source, target)['changedParts'])
            self.assertEqual(before, source.read_bytes())
            with zipfile.ZipFile(target) as package:
                for name, value in parts.items():
                    if name != 'word/document.xml':
                        self.assertEqual(value, package.read(name))
            with self.assertRaises(FileExistsError):
                conversion_copy(source, target)


if __name__ == '__main__':
    unittest.main()
