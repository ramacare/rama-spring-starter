package org.rama.starter.service.template;

import com.jayway.jsonpath.JsonPath;
import org.rama.starter.entity.asset.AssetFile;
import org.rama.starter.service.StorageService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class ReplacementProcessor {
    private final ReplacementHooks replacementHooks;
    private final StorageService storageService;

    public ReplacementProcessor(ReplacementHooks replacementHooks, StorageService storageService) {
        this.replacementHooks = replacementHooks;
        this.storageService = storageService;
    }

    public String parsePlaceholder(String placeholder, Map<String, String> attributeData) {
        String[] parts = placeholder.replaceAll("^\\{+|}+$", "").split(";");
        String replacementKey = parts[0].trim();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            String[] attribute = part.split("=", 2);
            String key = attribute[0].replaceAll("\\[.*?]", "").trim();
            attributeData.put(key, attribute.length > 1 ? attribute[1].trim().replaceAll("^[\"'“”‘’]|[\"'“”‘’]$", "") : "");
        }
        return replacementKey;
    }

    public String processReplacement(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = extractReplacement(replacementKey, replacements, attributes);
        String result = replacementHooks.process(replacement, attributes);
        if ((result == null || result.isBlank()) && attributes.containsKey("ifempty")) {
            return attributes.get("ifempty");
        }
        return result == null ? "" : result;
    }

    public Optional<byte[]> processBytes(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = extractReplacement(replacementKey, replacements, attributes);
        try {
            if (replacement instanceof byte[] bytes) {
                return Optional.of(bytes);
            }
            if (replacement instanceof AssetFile assetFile && assetFile.getId() != null) {
                return Optional.of(storageService.retrieve(assetFile.getId()).getContentAsByteArray());
            }
            if (replacement instanceof Map<?, ?> map) {
                Object id = map.containsKey("assetFileId") ? map.get("assetFileId") : map.get("id");
                if (id != null) {
                    return Optional.of(storageService.retrieve(Long.parseLong(String.valueOf(id))).getContentAsByteArray());
                }
                Object base64 = map.get("base64String");
                if (base64 != null) {
                    return Optional.of(Base64.getDecoder().decode(extractPureBase64(String.valueOf(base64))));
                }
            }

            String value = processReplacement(replacementKey, replacements, attributes);
            if (value.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(storageService.retrieve(Long.parseLong(value)).getContentAsByteArray());
            } catch (NumberFormatException ignored) {
            }
            try {
                return Optional.of(Base64.getDecoder().decode(extractPureBase64(value)));
            } catch (IllegalArgumentException ignored) {
            }
            try (InputStream inputStream = new FileInputStream(value)) {
                return Optional.of(inputStream.readAllBytes());
            } catch (Exception ignored) {
            }
            Resource resource = new ClassPathResource(value);
            try (InputStream inputStream = resource.getInputStream()) {
                return Optional.of(inputStream.readAllBytes());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private Object extractReplacement(String replacementKey, Map<String, Object> replacements, Map<String, String> attributes) {
        Object replacement = null;
        try {
            replacement = JsonPath.read(replacements, "$." + replacementKey);
        } catch (Exception ignored) {
        }
        if ((replacement == null || replacement.toString().isBlank()) && attributes.containsKey("else")) {
            try {
                replacement = JsonPath.read(replacements, "$." + attributes.get("else"));
            } catch (Exception ignored) {
            }
        }
        return replacement;
    }

    private String extractPureBase64(String base64Data) {
        return base64Data.contains("base64,") ? base64Data.substring(base64Data.indexOf("base64,") + 7).replaceAll("\\s+", "") : base64Data.replaceAll("\\s+", "");
    }
}
