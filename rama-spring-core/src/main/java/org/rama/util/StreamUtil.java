package org.rama.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class StreamUtil {
    private StreamUtil() {
    }

    public static Optional<InputStream> resettableInputStream(InputStream originalInputStream) {
        try {
            return Optional.of(new ByteArrayInputStream(toByteArray(originalInputStream)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static byte[] toByteArray(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        input.transferTo(buffer);
        return buffer.toByteArray();
    }

    public static InputStream toInputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    public static InputStream toInputStream(String base64String) {
        String base64EncodedData;
        if (base64String.contains("data:") && base64String.contains("base64,")) {
            base64EncodedData = base64String.split(",")[1];
        } else {
            base64EncodedData = base64String;
        }
        byte[] decodedBytes = Base64.getDecoder().decode(base64EncodedData);
        return new ByteArrayInputStream(decodedBytes);
    }

    public static InputStream inputStreamFromURL(String url) throws IOException {
        return URI.create(url).toURL().openStream();
    }

    public static byte[] generateZipBytes(Map<String, byte[]> files) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue());
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }
}
