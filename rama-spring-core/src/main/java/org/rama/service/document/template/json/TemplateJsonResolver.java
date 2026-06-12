package org.rama.service.document.template.json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a document-template code to its template JSON (a {@code DocumentTemplateItem} array),
 * used by {@link DocumentTemplateJsonRenderer} to expand {@code DocumentForm} nodes.
 *
 * <p>The starter ships a no-op default (returns {@link Optional#empty()}) via
 * {@code @ConditionalOnMissingBean}; a consumer wires a real resolver (e.g. backed by its
 * template store) to enable nested {@code DocumentForm} rendering.
 */
@FunctionalInterface
public interface TemplateJsonResolver {

    /**
     * @param templateCode the child template code (a {@code DocumentForm}'s {@code inputOptions})
     * @return the resolved template JSON, or empty when the code is unknown/unresolvable
     */
    Optional<List<Map<String, Object>>> resolve(String templateCode);
}
