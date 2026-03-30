package org.rama.service.document.template;

import java.util.Map;

public interface ReplacementTransformer {
    Map<String, Object> transform(Map<String, Object> replacements, String mrn, String encounterId);

    default String getTemplateCode() {
        return "";
    }

    default int getOrder() {
        return Integer.MAX_VALUE;
    }
}
