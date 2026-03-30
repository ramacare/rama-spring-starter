package org.rama.service.document.template;

import com.jayway.jsonpath.JsonPath;
import org.rama.entity.asset.AssetFile;
import org.rama.service.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class ReplacementProcessor {
    private static final Logger log = LoggerFactory.getLogger(ReplacementProcessor.class);

    private final ReplacementHooks replacementHooks;
    private final StorageProvider storageService;

    public ReplacementProcessor(ReplacementHooks replacementHooks, StorageProvider storageService) {
        this.replacementHooks = replacementHooks;
        this.storageService = storageService;
    }

    public String parsePlaceholder(String placeholder, Map<String, String> attributeData) {
        String[] placeholderData = placeholder.replaceAll("^\\{+|}+$", "").split(";");
        String replacementKey = placeholderData[0].trim();

        for (int i = 1; i < placeholderData.length; i++) {
            placeholderData[i] = placeholderData[i].trim();
            if (!placeholderData[i].isEmpty()) {
                String[] attribute = placeholderData[i].split("=", 2);
                attribute[0] = attribute[0].replaceAll("\\[.*?]", "").trim();
                if (attribute.length > 1) {
                    attribute[1] = attribute[1].trim().replaceAll("^[\"'“”‘’]|[\"'“”‘’]$", "");
                    attributeData.put(attribute[0].trim(), attribute[1]);
                } else {
                    attributeData.put(attribute[0].trim(), "");
                }
            }
        }

        return replacementKey;
    }

    public String processReplacement(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = extractReplacement(replacementKey, replacements, attributes);

        try {
            if (replacement == null) replacement = "";
            replacement = replacementHooks.process(replacement, attributes);
        } catch (Exception e) {
            log.debug("Attributes processing failed. {}", e.getMessage());
        }

        if ((replacement == null || replacement.toString().trim().isEmpty()) && attributes.containsKey("ifempty")) {
            replacement = attributes.get("ifempty").trim().replaceAll("^[\"'“”‘’]|[\"'“”‘’]$", "");
        }

        return replacement != null ? replacement.toString() : "";
    }

    public Optional<byte[]> processBytes(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = extractReplacement(replacementKey, replacements, attributes);
        String replacementString = processReplacement(replacementKey, replacements, attributes);

        if (replacement == null && (replacementString == null || replacementString.isEmpty())) {
            return Optional.empty();
        }

        try {
            // 0) Direct bytes / image
            if (replacement instanceof byte[] bytes) {
                return Optional.of(bytes);
            }
            if (replacement instanceof BufferedImage bi) {
                return Optional.of(bufferedImageToPngBytes(bi));
            }
            if (replacement instanceof ByteArrayOutputStream baos) {
                return Optional.of(baos.toByteArray());
            }

            // 1) Map case
            if (replacement instanceof Map<?, ?> mapReplacement) {
                Long idFromMap = extractAssetFileIdFromMap(mapReplacement);
                if (idFromMap != null) {
                    return Optional.of(storageService.retrieve(idFromMap).getContentAsByteArray());
                }

                Object base64Val = mapReplacement.get("base64String");
                if (base64Val != null && !base64Val.toString().isEmpty()) {
                    String pureBase64 = extractPureBase64(base64Val.toString());
                    return Optional.of(Base64.getDecoder().decode(cleanBase64(pureBase64)));
                }
            }

            // 2) AssetFile case
            if (replacement instanceof AssetFile assetFile) {
                Long id = assetFile.getId();
                if (id != null) {
                    return Optional.of(storageService.retrieve(id).getContentAsByteArray());
                }
            }

            // 3) InputStream case (read once + close)
            if (replacement instanceof InputStream is) {
                try (InputStream in = is) {
                    return Optional.of(in.readAllBytes());
                }
            }

            // 4) Fallback: treat as String (assetId -> base64 -> file path -> classpath)
            String value = replacementString;
            if (value == null || value.isEmpty()) return Optional.empty();
            value = value.trim();

            // 4.1 Try as asset id (Long)
            try {
                long assetId = Long.parseLong(value);
                return Optional.of(storageService.retrieve(assetId).getContentAsByteArray());
            } catch (NumberFormatException e) {
                log.debug("Value is not a numeric asset id, trying as base64/file/classpath");
            }

            // 4.2 Try as Base64 (raw string, maybe data URL)
            try {
                String pureBase64 = extractPureBase64(value);
                if (pureBase64 != null && !pureBase64.isEmpty()) {
                    return Optional.of(Base64.getDecoder().decode(cleanBase64(pureBase64)));
                }
            } catch (IllegalArgumentException ex) {
                log.debug("Value is not valid base64, trying as file path: {}", value);
            }

            // 4.3 Treat as file path
            try (InputStream fis = new FileInputStream(value)) {
                return Optional.of(fis.readAllBytes());
            } catch (FileNotFoundException ex) {
                log.debug("Value is not valid file path, trying as class path: {}", value);
            }

            // 4.4 Treat as class path
            Resource resource = new ClassPathResource(value);
            try (InputStream in = resource.getInputStream()) {
                return Optional.of(in.readAllBytes());
            }

        } catch (Exception e) {
            log.error("Failed to convert replacement to bytes", e);
            return Optional.empty();
        }
    }

    private Object extractReplacement(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = null;
        try {
            replacement = JsonPath.read(replacements, "$." + replacementKey);
        } catch (Exception e) {
            log.debug("Replacement not found. {}", e.getMessage());
        }

        if ((replacement == null || replacement.toString().trim().isEmpty()) && attributes.containsKey("else")) {
            String elseReplacementKey = replacementKey.contains(".")
                    ? replacementKey.replaceAll("\\.[^.]+$", "." + attributes.get("else"))
                    : attributes.get("else");
            try {
                replacement = JsonPath.read(replacements, "$." + elseReplacementKey);
            } catch (Exception e) {
                log.debug("Alternative Replacement not found. {}", e.getMessage());
            }
        }

        if (attributes.containsKey("if")) {
            String ifKey = replacementKey.contains(".")
                    ? replacementKey.replaceAll("\\.[^.]+$", "." + attributes.get("if"))
                    : attributes.get("if");
            try {
                Object ifValue = JsonPath.read(replacements, "$." + ifKey);
                if (ifValue == null
                        || (ifValue instanceof String && ((String) ifValue).trim().isEmpty())
                        || Boolean.FALSE.equals(ifValue)
                        || "false".equalsIgnoreCase(String.valueOf(ifValue))) {
                    replacement = null;
                }
            } catch (Exception e) {
                log.debug("Conditional value not found. {}", e.getMessage());
                replacement = null;
            }
        }

        return replacement;
    }

    private Long extractAssetFileIdFromMap(Map<?, ?> map) {
        Object assetFileIdVal = map.getOrDefault("assetFileId", null);
        Object idVal = map.getOrDefault("id", null);

        if (assetFileIdVal != null) idVal = assetFileIdVal;
        if (idVal == null) return null;

        if (idVal instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(idVal.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractPureBase64(String base64Data) {
        if (base64Data == null) return null;

        if (base64Data.contains("base64,")) {
            return base64Data.substring(base64Data.indexOf("base64,") + 7);
        }

        return base64Data;
    }

    private static String cleanBase64(String base64) {
        // tolerate whitespace/newlines
        return base64 == null ? "" : base64.replaceAll("\\s+", "");
    }

    private static byte[] bufferedImageToPngBytes(BufferedImage bi) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bi, "png", baos);
            return baos.toByteArray();
        }
    }
}
