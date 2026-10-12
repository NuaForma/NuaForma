/* SPDX-License-Identifier: Apache-2.0 */
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDMetadata;

/** Bounded-process research only: profile-gap inspection, never automatic product repair. */
public final class PdfaProbe {
    public static void main(String[] args) throws Exception {
        try (PDDocument document = Loader.loadPDF(Path.of(args[1]).toFile())) {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            String xmp = new String(document.getDocumentCatalog().getMetadata().exportXMPMetadata().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (args[0].equals("relabel")) {
                String from = "<pdfaid:conformance>B</pdfaid:conformance>";
                if (!xmp.contains("<pdfaid:part>3</pdfaid:part>") || xmp.indexOf(from) < 0 || xmp.indexOf(from) != xmp.lastIndexOf(from)) throw new IllegalArgumentException("Expected one PDF/A-3b identifier");
                PDMetadata metadata = new PDMetadata(document);
                metadata.importXMPMetadata(xmp.replace(from, "<pdfaid:conformance>A</pdfaid:conformance>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                document.getDocumentCatalog().setMetadata(metadata);
                document.save(Path.of(args[1]).resolveSibling("relabelled.pdf").toFile());
                return;
            }
            if (args[0].startsWith("negative")) {
                switch (args[0]) {
                    case "negative" -> catalog.removeItem(COSName.STRUCT_TREE_ROOT);
                    case "negative-lang" -> catalog.setString(COSName.LANG, "???");
                    case "negative-icc" -> catalog.removeItem(COSName.OUTPUT_INTENTS);
                    case "negative-alt" -> {
                        ArrayDeque<COSBase> nodes = new ArrayDeque<>();
                        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        nodes.add(catalog.getDictionaryObject(COSName.STRUCT_TREE_ROOT));
                        while (!nodes.isEmpty()) {
                            COSBase node = nodes.removeLast();
                            if (!visited.add(node)) continue;
                            if (visited.size() > 500000) throw new IllegalArgumentException("Research inspection budget exceeded");
                            if (node instanceof COSObject object && object.getObject() != null) nodes.add(object.getObject());
                            else if (node instanceof COSArray array) for (COSBase entry : array) nodes.add(entry);
                            else if (node instanceof COSDictionary dictionary) {
                                dictionary.removeItem(COSName.ALT);
                                for (COSBase entry : dictionary.getValues()) if (entry != null) nodes.add(entry);
                            }
                        }
                    }
                    case "negative-unicode" -> {
                        for (PDPage page : document.getPages())
                            for (COSName name : page.getResources().getFontNames()) page.getResources().getFont(name).getCOSObject().removeItem(COSName.TO_UNICODE);
                    }
                    default -> throw new IllegalArgumentException("Unknown negative probe");
                }
                document.save(Path.of(args[1]).resolveSibling("negative.pdf").toFile());
                return;
            }
            System.out.println("pages=" + document.getNumberOfPages() + "; language=" + catalog.getString(COSName.LANG) + "; structure=" + catalog.containsKey(COSName.STRUCT_TREE_ROOT));
            System.out.println(xmp);
            ArrayDeque<COSBase> pending = new ArrayDeque<>();
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<String, Integer> roles = new TreeMap<>();
            int alts = 0, actualTexts = 0;
            COSBase structure = catalog.getDictionaryObject(COSName.STRUCT_TREE_ROOT);
            if (structure != null) pending.add(structure);
            while (!pending.isEmpty()) {
                COSBase value = pending.removeLast();
                if (!seen.add(value)) continue;
                if (seen.size() > 500000) throw new IllegalArgumentException("Research inspection budget exceeded");
                if (value instanceof COSObject object && object.getObject() != null) pending.add(object.getObject());
                else if (value instanceof COSArray array) for (COSBase element : array) pending.add(element);
                else if (value instanceof COSDictionary dictionary) {
                    String role = dictionary.getNameAsString(COSName.S);
                    if (role != null) roles.merge(role, 1, Integer::sum);
                    if (dictionary.containsKey(COSName.ALT)) { alts++; System.out.println("ALT=" + dictionary.getString(COSName.ALT)); }
                    if (dictionary.containsKey(COSName.ACTUAL_TEXT)) { actualTexts++; System.out.println("ACTUALTEXT=" + dictionary.getString(COSName.ACTUAL_TEXT)); }
                    for (COSBase element : dictionary.getValues()) if (element != null) pending.add(element);
                }
            }
            System.out.println("roles=" + roles + "; alternateDescriptions=" + alts + "; actualTexts=" + actualTexts);
        }
    }
}
