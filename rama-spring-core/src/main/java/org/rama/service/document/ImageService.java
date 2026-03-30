package org.rama.service.document;

import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ImageService {
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    private final Tika tika = new Tika();
    private final MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

    public byte[] convertAuto(byte[] inputBytes, boolean lossless) {
        if (inputBytes == null || inputBytes.length == 0) return inputBytes;

        try {
            byte[] outputBytes;

            if (isBlackAndWhiteImage(inputBytes)) {
                if (isTiffGroup4(inputBytes)) {
                    outputBytes = inputBytes;
                } else {
                    outputBytes = convertToTiffGroup4(inputBytes);
                }
            } else {
                outputBytes = convertToWebp(inputBytes, lossless);
            }

            if (outputBytes == null || outputBytes.length == 0) return inputBytes;
            return (outputBytes.length > inputBytes.length) ? inputBytes : outputBytes;

        } catch (Exception e) {
            log.debug("convertAutoBlocking fallback: {}", e.getMessage());
            return inputBytes;
        }
    }

    /**
     * True if the contentType is an image we can decode with ImageIO.
     */
    public boolean isContentTypeConvertible(String contentType) {
        if (contentType == null || contentType.isBlank()) return false;

        MediaType mt;
        try {
            mt = MediaType.parse(contentType);
        } catch (Exception e) {
            return false;
        }

        if (mt == null) return false;
        if (!"image".equalsIgnoreCase(mt.getType())) return false;

        String subtype = mt.getSubtype();
        if (subtype == null || subtype.isBlank()) return false;

        String fmt = switch (subtype.toLowerCase()) {
            case "jpeg" -> "jpg";
            case "x-ms-bmp" -> "bmp";
            case "x-icon" -> "ico";
            default -> subtype.toLowerCase();
        };

        try {
            return ImageIO.getImageReadersByFormatName(fmt).hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] convertToAvif(byte[] inputBytes) throws IOException {
        try {
            return convertByProcessBytes(inputBytes, "avif",
                    List.of("avifenc", "--lossless", "{input}", "{output}"),
                    true);
        } catch (Exception ignore) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (img == null) throw new IOException("Unable to decode image");
            return convertByProcessBufferedImage(img, "avif",
                    List.of("avifenc", "--lossless", "{input}", "{output}"));
        }
    }

    public byte[] convertToWebp(byte[] inputBytes, boolean lossless) throws IOException {
        List<String> cmd = lossless
                ? List.of("cwebp", "-lossless", "{input}", "-o", "{output}")
                : List.of("cwebp", "-q", "100", "{input}", "-o", "{output}");
        try {
            return convertByProcessBytes(inputBytes, "webp", cmd, true);
        } catch (Exception ignore) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (img == null) throw new IOException("Unable to decode image");
            return convertByProcessBufferedImage(img, "webp", cmd);
        }
    }

    public byte[] convertToTiffGroup4(byte[] inputBytes) throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(inputBytes)) {
            return convertToTiffGroup4(in);
        }
    }

    public byte[] convertToTiffGroup4(InputStream inputStream) throws Exception {
        try (InputStream is = inputStream;
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(is)) {

            if (iis == null) throw new IOException("Unable to create ImageInputStream");

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw new UnsupportedOperationException("No ImageReader available");

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);

                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
                if (!writers.hasNext()) throw new UnsupportedOperationException("No TIFF writer available");

                ImageWriter writer = writers.next();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType("CCITT T.6");

                    writer.setOutput(ios);

                    int numImages = safeNumImages(reader);
                    for (int i = 0; i < numImages; i++) {
                        BufferedImage image = reader.read(i);
                        BufferedImage bw = ensureBinaryBW(image);

                        IIOMetadata metadata = reader.getImageMetadata(i);
                        IIOImage iio = new IIOImage(bw, null, metadata);

                        if (i == 0) writer.write(null, iio, param);
                        else writer.writeInsert(i, iio, param);
                    }

                    ios.flush();
                    return out.toByteArray();
                } finally {
                    writer.dispose();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    public boolean isBlackAndWhiteImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                BufferedImage image = reader.read(0);
                return isBlackAndWhiteImage(image);
            } finally {
                reader.dispose();
            }
        } catch (Exception ignore) {
            return false;
        }
    }

    public boolean isBlackAndWhiteImage(BufferedImage image) {
        if (image == null) return false;
        ColorModel colorModel = image.getColorModel();
        int csType = colorModel.getColorSpace().getType();
        return (csType == ColorSpace.TYPE_RGB || csType == ColorSpace.TYPE_GRAY) && colorModel.getPixelSize() == 1;
    }

    public boolean isTiffGroup4(byte[] bytes) {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInputStream == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) return false;

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream);
                IIOMetadata metadata = reader.getImageMetadata(0);

                Node rootNode = metadata.getAsTree(metadata.getNativeMetadataFormatName());

                IIOMetadataNode tiffDirectory = (IIOMetadataNode) rootNode.getFirstChild();
                NodeList tiffFields = tiffDirectory.getElementsByTagName("TIFFField");

                Node compressionNode = null;
                for (int i = 0; i < tiffFields.getLength(); i++) {
                    Node n = tiffFields.item(i);
                    Node nameAttr = n.getAttributes().getNamedItem("name");
                    if (nameAttr != null && "Compression".equals(nameAttr.getNodeValue())) {
                        compressionNode = n;
                        break;
                    }
                }

                if (compressionNode != null) {
                    IIOMetadataNode compression = (IIOMetadataNode) compressionNode.getFirstChild().getFirstChild();
                    String compressionType = compression.getAttribute("value");
                    return "4".equals(compressionType);
                }
            } finally {
                reader.dispose();
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    // =========================
    // Internal helpers
    // =========================

    private BufferedImage ensureBinaryBW(BufferedImage image) {
        if (image == null) return null;
        if (isBlackAndWhiteImage(image)) return image;

        BufferedImage bw = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        var g = bw.getGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return bw;
    }

    private int safeNumImages(ImageReader reader) {
        try {
            return reader.getNumImages(true);
        } catch (Exception e) {
            return 1;
        }
    }

    private String detectExtension(byte[] bytes) {
        String mime = tika.detect(bytes);
        try {
            String ext = mimeTypes.forName(mime).getExtension();
            return (ext == null || ext.isBlank()) ? ".bin" : ext;
        } catch (MimeTypeException e) {
            return ".bin";
        }
    }

    private byte[] convertByProcessBufferedImage(BufferedImage inputImage, String outputExt, List<String> cmdTemplate) throws IOException {
        File inputFile = File.createTempFile("img-in-", ".png");
        File outputFile = File.createTempFile("img-out-", "." + outputExt);
        try {
            ImageIO.write(inputImage, "png", inputFile);

            int exit = runProcess(expand(cmdTemplate, inputFile.getAbsolutePath(), outputFile.getAbsolutePath()));
            if (exit != 0) throw new IOException("process exited with " + exit);

            return Files.readAllBytes(outputFile.toPath());
        } finally {
            safeDelete(inputFile);
            safeDelete(outputFile);
        }
    }

    private byte[] convertByProcessBytes(byte[] inputBytes, String outputExt, List<String> cmdTemplate, boolean copyExif) throws IOException {
        File inputFile = File.createTempFile("img-in-", detectExtension(inputBytes));
        File outputFile = File.createTempFile("img-out-", "." + outputExt);
        try {
            Files.write(inputFile.toPath(), inputBytes);

            int exit = runProcess(expand(cmdTemplate, inputFile.getAbsolutePath(), outputFile.getAbsolutePath()));
            if (exit != 0) throw new IOException("process exited with " + exit);

            if (copyExif) {
                try {
                    runProcess(expand(
                            List.of("exiftool", "-overwrite_original", "-TagsFromFile", "{input}", "{output}"),
                            inputFile.getAbsolutePath(), outputFile.getAbsolutePath()
                    ));
                } catch (Exception e) {
                    log.debug("exiftool skipped: {}", e.getMessage());
                }
            }

            return Files.readAllBytes(outputFile.toPath());
        } finally {
            safeDelete(inputFile);
            safeDelete(outputFile);
        }
    }

    private List<String> expand(List<String> template, String input, String output) {
        List<String> cmd = new ArrayList<>(template.size());
        for (String s : template) {
            if ("{input}".equals(s)) cmd.add(input);
            else if ("{output}".equals(s)) cmd.add(output);
            else cmd.add(s);
        }
        log.debug("exec: {}", cmd);
        return cmd;
    }

    private int runProcess(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.debug(line);
        }

        try {
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("process timeout");
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        }
    }

    private static void safeDelete(File f) {
        try {
            if (f != null) Files.deleteIfExists(f.toPath());
        } catch (Exception ignore) {
        }
    }
}
