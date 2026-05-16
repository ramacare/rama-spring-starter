package org.rama.cors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.service.idempotency.IdempotencyProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class IdempotencyHeaderSupportTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<IdempotencyProperties> provider = mock(ObjectProvider.class);

    @Test
    void resolveHeaderName_fallsBackToDefault_whenProviderIsEmpty() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(IdempotencyHeaderSupport.resolveHeaderName(provider))
                .isEqualTo("Idempotency-Key");
    }

    @Test
    void resolveHeaderName_fallsBackToDefault_whenConfiguredNameIsBlank() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setHeaderName("   ");
        when(provider.getIfAvailable()).thenReturn(properties);

        assertThat(IdempotencyHeaderSupport.resolveHeaderName(provider))
                .isEqualTo("Idempotency-Key");
    }

    @Test
    void resolveHeaderName_fallsBackToDefault_whenConfiguredNameIsNull() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setHeaderName(null);
        when(provider.getIfAvailable()).thenReturn(properties);

        assertThat(IdempotencyHeaderSupport.resolveHeaderName(provider))
                .isEqualTo("Idempotency-Key");
    }

    @Test
    void resolveHeaderName_returnsConfiguredName_whenSet() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setHeaderName("X-My-Idem");
        when(provider.getIfAvailable()).thenReturn(properties);

        assertThat(IdempotencyHeaderSupport.resolveHeaderName(provider))
                .isEqualTo("X-My-Idem");
    }

    @Test
    void containsIgnoreCase_returnsFalseForNullOrEmptyInputs() {
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(null, "Idempotency-Key")).isFalse();
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(List.of("Authorization"), null)).isFalse();
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(List.of(), "Idempotency-Key")).isFalse();
    }

    @Test
    void containsIgnoreCase_matchesRegardlessOfCase() {
        List<String> headers = Arrays.asList("Authorization", "idempotency-KEY", "Content-Type");

        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(headers, "Idempotency-Key")).isTrue();
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(headers, "IDEMPOTENCY-KEY")).isTrue();
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(headers, "X-Other")).isFalse();
    }

    @Test
    void containsIgnoreCase_skipsNullEntriesWithoutThrowing() {
        List<String> headersWithNull = Arrays.asList("Authorization", null, "Idempotency-Key");

        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(headersWithNull, "Idempotency-Key")).isTrue();
        assertThat(IdempotencyHeaderSupport.containsIgnoreCase(headersWithNull, "Missing")).isFalse();
    }
}
