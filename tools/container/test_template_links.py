import io
import unittest
import zipfile
from qualify_template_links import verify_docx


class TemplateLinkVerificationTest(unittest.TestCase):
    @staticmethod
    def document(text, settings=b'<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>', relationship=b''):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('word/document.xml', ('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>' + text + '</w:p></w:body></w:document>').encode())
            archive.writestr('word/settings.xml', settings)
            archive.writestr('word/_rels/settings.xml.rels', relationship)
        return output.getvalue()

    def test_complete_text_can_span_separate_formatted_runs(self):
        verify_docx(self.document('<w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>Passive template</w:t></w:r>'))

    def test_missing_or_changed_generated_text_is_rejected(self):
        for text in ('Hello', 'Hello {name}', 'Hello another tenant', 'Hello Passive template EXTRA'):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                verify_docx(self.document('<w:r><w:t>' + text + '</w:t></w:r>'))

    def test_retained_attachment_or_external_target_is_rejected(self):
        text = '<w:r><w:t>Hello Passive template</w:t></w:r>'
        with self.assertRaises(AssertionError):
            verify_docx(self.document(text, settings=b'<attachedTemplate/>'))
        with self.assertRaises(AssertionError):
            verify_docx(self.document(text, relationship=b'<Relationship Target="https://PRIVATE_TEMPLATE.example/template.dotx"/>'))


if __name__ == '__main__': unittest.main()
