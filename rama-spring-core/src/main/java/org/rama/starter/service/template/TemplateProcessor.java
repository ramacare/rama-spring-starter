package org.rama.starter.service.template;

import java.io.InputStream;
import java.util.Map;

public interface TemplateProcessor {
    byte[] processTemplate(InputStream templateInputStream, Map<String, Object> replacements);
}
