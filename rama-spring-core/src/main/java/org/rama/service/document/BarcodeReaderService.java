package org.rama.service.document;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

public class BarcodeReaderService {
    public String scanQRCode(String base64String) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64String);
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return scanQRCode(bufferedImage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process QR code", e);
        }
    }

    public String scanQRCode(BufferedImage bufferedImage) {
        try {
            if (bufferedImage == null) {
                throw new IllegalArgumentException("Invalid image data");
            }
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, BarcodeFormat.QR_CODE);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            try {
                return decodeImage(bufferedImage, hints);
            } catch (NotFoundException e) {
                return scanInRegions(bufferedImage, hints);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process QR code", e);
        }
    }

    private String decodeImage(BufferedImage bufferedImage, Map<DecodeHintType, Object> hints) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(bufferedImage);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result result = new MultiFormatReader().decode(bitmap, hints);
        return result.getText();
    }

    private String scanInRegions(BufferedImage bufferedImage, Map<DecodeHintType, Object> hints) throws NotFoundException {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        int gridRows = 4;
        int gridCols = 4;
        int regionWidth = width / gridCols;
        int regionHeight = height / gridRows;
        double overlapPercentage = 0.20;
        int overlapWidth = (int) (regionWidth * overlapPercentage);
        int overlapHeight = (int) (regionHeight * overlapPercentage);

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                int x = Math.max(0, col * regionWidth - overlapWidth);
                int y = Math.max(0, row * regionHeight - overlapHeight);
                int regionEndX = Math.min(width, (col + 1) * regionWidth + overlapWidth);
                int regionEndY = Math.min(height, (row + 1) * regionHeight + overlapHeight);
                int regionActualWidth = regionEndX - x;
                int regionActualHeight = regionEndY - y;
                try {
                    BufferedImage subImage = bufferedImage.getSubimage(x, y, regionActualWidth, regionActualHeight);
                    return decodeImage(subImage, hints);
                } catch (NotFoundException ignored) {
                }
            }
        }

        throw new RuntimeException("No QR code found in the image");
    }
}
