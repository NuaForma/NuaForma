package dev.nuaforma.server;

import java.io.File;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test-only independent PDFBox inspection; never included in application artifacts. */
public final class PdfPolicyInspector {
    private PdfPolicyInspector() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected synthetic PDF and tenant references");
        Class<?> documentType = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
        Object document = Class.forName("org.apache.pdfbox.Loader").getMethod("loadPDF", File.class).invoke(null, new File(args[0]));
        try {
            Class<?> stripperType = Class.forName("org.apache.pdfbox.text.PDFTextStripper");
            Object stripper = stripperType.getConstructor().newInstance();
            String text = (String) stripperType.getMethod("getText", documentType).invoke(stripper, document);
            if (!text.contains(args[1]) || text.contains(args[2]) || !text.contains("NF001")) throw new AssertionError("Tenant/barcode content mismatch");
            int pages = (int) documentType.getMethod("getNumberOfPages").invoke(document);
            if (pages != 1) throw new AssertionError("Unexpected pagination");
            Object page = documentType.getMethod("getPage", int.class).invoke(document, 0);
            Object box = page.getClass().getMethod("getMediaBox").invoke(page);
            float width = (float) box.getClass().getMethod("getWidth").invoke(box);
            float height = (float) box.getClass().getMethod("getHeight").invoke(box);
            if (width < 500 || width > 700 || height < 700 || height > 900) throw new AssertionError("Unexpected page geometry");
            Class<?> rendererType = Class.forName("org.apache.pdfbox.rendering.PDFRenderer");
            Object renderer = rendererType.getConstructor(documentType).newInstance(document);
            BufferedImage image = (BufferedImage) rendererType.getMethod("renderImage", int.class).invoke(renderer, 0);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    digest.update((byte) (pixel >> 16));
                    digest.update((byte) (pixel >> 8));
                    digest.update((byte) pixel);
                }
            }
            System.out.println("Synthetic content, tenant isolation, barcode text and page geometry: PASS");
            System.out.println("layoutSha256=" + HexFormat.of().formatHex(digest.digest()));
        } finally {
            ((AutoCloseable) document).close();
        }
    }
}
