package com.eduplatform.eduplatform_backend.support;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Upload fixtures. Each is a genuine file of its type, not just the right magic bytes, so the
 * tests hold even if the upload gate starts parsing further than the signature.
 */
public final class TestFiles {

    private TestFiles() {}

    /** A 4x4 PNG written by the JDK's own encoder. */
    public static byte[] png() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x1E90FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("The JDK has no PNG writer");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A minimal PDF: a catalog, an empty page tree and a trailer. */
    public static byte[] pdf() {
        return """
                %PDF-1.4
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """.getBytes(StandardCharsets.US_ASCII);
    }

    /** An ISO base media file: an ftyp box declaring the isom brand, then an empty free box. */
    public static byte[] mp4() {
        return new byte[]{
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
                'i', 's', 'o', 'm', 'i', 's', 'o', '2',
                0, 0, 0, 8, 'f', 'r', 'e', 'e'};
    }
}
