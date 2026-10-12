/* SPDX-License-Identifier: Apache-2.0 */
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.text.*;

/** Exposes tagged reading order for human review; machine conformance is not meaning. */
public final class SemanticProbe {
    static final Map<COSDictionary, Map<Integer, String>> pageText = new IdentityHashMap<>();
    static int visits;
    static void collect(List<Object> objects, Map<Integer, String> result) {
        for (Object object : objects) if (object instanceof PDMarkedContent marked) {
            StringBuilder text = new StringBuilder();
            for (Object content : marked.getContents()) if (content instanceof TextPosition position) text.append(position.getUnicode());
            String actual = marked.getActualText();
            result.put(marked.getMCID(), actual != null ? actual : text.toString());
            collect(marked.getContents(), result);
        }
    }
    static void walk(COSBase item, COSDictionary page, COSDictionary roles, int depth) {
        if (item == null) return;
        if (++visits > 500000 || depth > 128) throw new IllegalArgumentException("Semantic inspection budget exceeded");
        if (item instanceof COSObject object) walk(object.getObject(), page, roles, depth + 1);
        else if (item instanceof COSArray array) for (COSBase child : array) walk(child, page, roles, depth + 1);
        else if (item instanceof COSInteger number) System.out.println("TEXT " + pageText.getOrDefault(page, Map.of()).getOrDefault(number.intValue(), ""));
        else if (item instanceof COSDictionary dictionary) {
            COSDictionary ownPage = dictionary.getCOSDictionary(COSName.PG);
            if (ownPage != null) page = ownPage;
            String role = dictionary.getNameAsString(COSName.S);
            if (role != null) {
                String mapped = roles == null ? null : roles.getNameAsString(COSName.getPDFName(role));
                System.out.println("ROLE " + role + " -> " + (mapped == null ? role : mapped));
            }
            for (COSName key : List.of(COSName.ALT, COSName.ACTUAL_TEXT)) if (dictionary.containsKey(key)) System.out.println(key.getName() + " " + dictionary.getString(key));
            if (dictionary.containsKey(COSName.MCID)) walk(dictionary.getDictionaryObject(COSName.MCID), page, roles, depth + 1);
            walk(dictionary.getDictionaryObject(COSName.K), page, roles, depth + 1);
        }
    }
    public static void main(String[] args) throws Exception {
        try (PDDocument document = Loader.loadPDF(Path.of(args[0]).toFile())) {
            for (PDPage page : document.getPages()) {
                PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
                extractor.processPage(page);
                Map<Integer, String> marked = new HashMap<>();
                collect(new ArrayList<>(extractor.getMarkedContents()), marked);
                pageText.put(page.getCOSObject(), marked);
            }
            COSDictionary root = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
            walk(root.getDictionaryObject(COSName.K), null, root.getCOSDictionary(COSName.ROLE_MAP), 0);
        }
    }
}
