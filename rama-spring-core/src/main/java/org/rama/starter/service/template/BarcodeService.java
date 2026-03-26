package org.rama.starter.service.template;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public class BarcodeService {
    public BufferedImage generateQRCode(String text, int width, int height) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 0);
        BitMatrix bitMatrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    public BufferedImage generateCode39(String text) {
        BitMatrix bitMatrix = new Code39Writer().encode(text, BarcodeFormat.CODE_39, Math.max(100, text.length() * 10), 50, Map.of(EncodeHintType.MARGIN, 2));
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    public BufferedImage generateCode128(String text) {
        BitMatrix bitMatrix = new Code128Writer().encode(text, BarcodeFormat.CODE_128, Math.max(100, text.length() * 10), 50, Map.of(EncodeHintType.MARGIN, 2));
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }
}
