package org.rama.service.document.template;

import java.io.InputStream;
import java.util.Map;

public interface TemplateProcessor {
    byte[] processTemplate(InputStream templateInputStream, Map<String, Object> replacements);
}
