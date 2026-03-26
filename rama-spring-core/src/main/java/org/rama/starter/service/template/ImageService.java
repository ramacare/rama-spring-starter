package org.rama.starter.service.template;

import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class ImageService {
    private final Tika tika = new Tika();

    public boolean isContentTypeConvertible(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        MediaType mediaType = MediaType.parse(contentType);
        return mediaType != null && "image".equalsIgnoreCase(mediaType.getType());
    }

    public String detectContentType(byte[] bytes) {
        return tika.detect(bytes);
    }

    public byte[] toPngBytes(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }

    public BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
