"""Independent automatic DOCX embedding checks against the actual shipped IMAGE/MOUNT catalogs."""
import hashlib
import io
import json
import xml.etree.ElementTree as ET
import zipfile
from xml.sax.saxutils import escape
from qualify_service import generate, template

WORD = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
OFFICE = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
FACES = dict(regular="embedRegular", bold="embedBold", italic="embedItalic", boldItalic="embedBoldItalic")


def embedded(document):
    result = {}
    with zipfile.ZipFile(io.BytesIO(document)) as archive:
        table = ET.fromstring(archive.read("word/fontTable.xml"))
        relationships = {item.attrib["Id"]: item.attrib["Target"]
                         for item in ET.fromstring(archive.read("word/_rels/fontTable.xml.rels"))}
        for entry in table:
            for face, tag in FACES.items():
                reference = entry.find(WORD + tag)
                if reference is None:
                    continue
                target = relationships[reference.attrib[OFFICE + "id"]]
                assert target.startswith("fonts/") and ".." not in target
                data = bytearray(archive.read("word/" + target))
                key = reference.attrib[WORD + "fontKey"].translate(str.maketrans("", "", "{}-"))
                mask = bytes.fromhex(key)[::-1]
                for index in range(32):
                    data[index] ^= mask[index % 16]
                result[entry.attrib[WORD + "name"], face] = bytes(data)
    return result


def qualify(base, catalog, output):
    image = {}
    for font in catalog:
        if font["source"] == "bundled" and font.get("resource"):
            image.setdefault(font["family"], set()).add(font["face"])
    families = sorted(family for family, faces in image.items() if set(FACES) <= faces)[:2]
    assert len(families) == 2, "IMAGE must supply two independently named complete families"
    wanted = {(family, face) for family in families for face in FACES} | {("Libre Barcode 39", "regular")}
    selected = {(font["family"], font["face"]): font for font in catalog if (font["family"], font["face"]) in wanted}
    assert selected["Libre Barcode 39", "regular"]["source"] == "mounted"
    paragraphs = []
    for family, face in sorted(wanted):
        formatting = ('<w:b/>' if face in ("bold", "boldItalic") else '') + ('<w:i/>' if face in ("italic", "boldItalic") else '')
        text = "*NF001*" if family == "Libre Barcode 39" else "NuaForma automatic font selection " + face
        paragraphs.append('<w:p><w:r><w:rPr><w:rFonts w:ascii="' + escape(family, {'"': '&quot;'}) + '" w:hAnsi="' +
                          escape(family, {'"': '&quot;'}) + '"/>' + formatting + '</w:rPr><w:t>' + text + '</w:t></w:r></w:p>')
    source = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(template())) as incoming, zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as outgoing:
        for name in incoming.namelist():
            data = incoming.read(name)
            if name == "word/styles.xml":
                data = data.replace(b"Liberation Serif", escape(families[0], {'"': '&quot;'}).encode())
            if name == "word/document.xml":
                root = ET.fromstring(data)
                section = ET.tostring(root.find(WORD + "body/" + WORD + "sectPr"), encoding="unicode")
                data = ('<w:document xmlns:w="' + WORD[1:-1] + '"><w:body>' + ''.join(paragraphs) + section + '</w:body></w:document>').encode()
            outgoing.writestr(name, data)
    options = {"fontMode": "embed", "fontSelection": "used"}
    status, _, document = generate(base, "docx", source.getvalue(), {}, options)
    assert status == 200, (status, document[:1024])
    fonts = embedded(document)
    assert fonts.keys() == wanted
    for identity, data in fonts.items():
        assert selected[identity]["resource"] == "nf-mounted-" + hashlib.sha256(data).hexdigest()
    (output / "automatic-fonts.docx").write_bytes(document)
    status, _, repeated = generate(base, "docx", document, {}, options)
    assert status == 200 and embedded(repeated) == fonts
    status, _, preserved = generate(base, "docx", source.getvalue(), {})
    assert status == 200
    with zipfile.ZipFile(io.BytesIO(preserved)) as archive:
        assert not any(name.endswith(".odttf") for name in archive.namelist())
    status, _, pdf = generate(base, "pdf", document, {})
    assert status == 200 and pdf.startswith(b"%PDF-1.7"), (status, pdf[:1024])
    (output / "automatic-fonts.pdf").write_bytes(pdf)
    (output / "automatic-fonts.json").write_text(json.dumps({"families": families, "faces": sorted(wanted),
        "checks": ["exact-deobfuscated-SHA256-and-relationships", "used-faces-only", "IMAGE-and-MOUNT", "embedded-preservation", "default-preserve", "actual-validated-PDF-render"]}, indent=2))
