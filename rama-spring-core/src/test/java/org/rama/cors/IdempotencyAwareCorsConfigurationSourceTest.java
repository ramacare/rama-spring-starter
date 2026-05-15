package org.rama.cors;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

@Tag("unit")
class IdempotencyAwareCorsConfigurationSourceTest {

    private static final String HEADER = "Idempotency-Key";

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private IdempotencyAwareCorsConfigurationSource newSource(CorsConfiguration cfg) {
        return new IdempotencyAwareCorsConfigurationSource(req -> cfg, HEADER);
    }

    @Test
    void getCorsConfiguration_returnsNull_whenDelegateReturnsNull() {
        CorsConfigurationSource source = new IdempotencyAwareCorsConfigurationSource(req -> null, HEADER);

        assertThat(source.getCorsConfiguration(request)).isNull();
    }

    @Test
    void getCorsConfiguration_leavesAllowedHeadersAlone_whenNull() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Don't set allowedHeaders → null means "allow all" in Spring's contract.

        CorsConfiguration result = newSource(cfg).getCorsConfiguration(request);

        assertThat(result).isSameAs(cfg);
        assertThat(result.getAllowedHeaders()).isNull();
    }

    @Test
    void getCorsConfiguration_leavesAllowedHeadersAlone_whenWildcard() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(List.of("*"));

        CorsConfiguration result = newSource(cfg).getCorsConfiguration(request);

        assertThat(result).isSameAs(cfg);
        assertThat(result.getAllowedHeaders()).containsExactly("*");
    }

    @Test
    void getCorsConfiguration_leavesUnchanged_whenHeaderAlreadyPresent() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(new ArrayList<>(Arrays.asList("Authorization", "Idempotency-Key", "Content-Type")));

        CorsConfiguration result = newSource(cfg).getCorsConfiguration(request);

        assertThat(result).isSameAs(cfg);
        assertThat(result.getAllowedHeaders()).containsExactly("Authorization", "Idempotency-Key", "Content-Type");
    }

    @Test
    void getCorsConfiguration_isCaseInsensitiveForExistingHeader() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(new ArrayList<>(Arrays.asList("Authorization", "idempotency-key", "Content-Type")));

        CorsConfiguration result = newSource(cfg).getCorsConfiguration(request);

        // Already present (different casing) → don't duplicate.
        assertThat(result).isSameAs(cfg);
        assertThat(result.getAllowedHeaders()).hasSize(3);
    }

    @Test
    void getCorsConfiguration_appendsHeader_whenMissing() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(new ArrayList<>(Arrays.asList("Authorization", "Content-Type")));

        CorsConfiguration result = newSource(cfg).getCorsConfiguration(request);

        assertThat(result).isNotSameAs(cfg);
        assertThat(result.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "Idempotency-Key");
    }

    @Test
    void getCorsConfiguration_doesNotMutateDelegateConfiguration_acrossRequests() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(new ArrayList<>(Arrays.asList("Authorization")));
        IdempotencyAwareCorsConfigurationSource source = newSource(cfg);

        // Call it twice; the original config must remain pristine each time.
        source.getCorsConfiguration(request);
        source.getCorsConfiguration(request);

        assertThat(cfg.getAllowedHeaders()).containsExactly("Authorization");
    }

    @Test
    void getCorsConfiguration_appendsCustomHeaderName_whenOverridden() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedHeaders(new ArrayList<>(Arrays.asList("Authorization", "Content-Type")));
        CorsConfigurationSource source = new IdempotencyAwareCorsConfigurationSource(req -> cfg, "X-Custom-Idem");

        CorsConfiguration result = source.getCorsConfiguration(request);

        assertThat(result.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "X-Custom-Idem");
    }

    @Test
    void constructor_rejectsNullDelegate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyAwareCorsConfigurationSource(null, HEADER));
    }

    @Test
    void constructor_rejectsBlankHeaderName() {
        CorsConfigurationSource delegate = req -> null;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyAwareCorsConfigurationSource(delegate, ""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyAwareCorsConfigurationSource(delegate, "  "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyAwareCorsConfigurationSource(delegate, null));
    }
}
