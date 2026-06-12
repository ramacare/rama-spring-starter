package org.rama.service.document.template;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a base-template code (declared via the {@code BaseTemplate} docx custom
 * property) to an already-preprocessed docx stream.
 *
 * <p>The starter has no concept of template codes — the DB {@code document_template}
 * table and the {@code classpath:documents/templates/<code>.docx} fallback live in the
 * consumer application. Consumers implement this interface (typically by wrapping their
 * existing {@code TemplateResolver} + {@code TemplatePreprocessor}) and register it as a
 * Spring bean. The starter ships a no-op default that returns {@link Optional#empty()},
 * leaving the print pipeline unchanged until a consumer opts in.
 *
 * <p>The returned stream MUST already be preprocessed (the same output the consumer feeds
 * to {@code DocxTemplateProcessor.processTemplate} for a normal print), so base-template
 * resolution reuses the existing {@code docx-template-cache$} cache.
 */
public interface BaseTemplateResolver {

    /**
     * @param templateCode the value of the {@code BaseTemplate} custom property
     * @param replacements the print data map (for consumers that select a variant by data)
     * @return the preprocessed base docx stream (the caller is responsible for closing it),
     *         or {@link Optional#empty()} if no template is found for the code (the caller
     *         then renders the original template normally)
     */
    Optional<InputStream> resolve(String templateCode, Map<String, Object> replacements);
}
