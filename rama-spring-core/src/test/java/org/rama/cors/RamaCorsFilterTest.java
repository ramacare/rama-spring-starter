package org.rama.cors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class RamaCorsFilterTest {

    private static final String ORIGINS = "https://example.test";
    private static final String METHODS = "POST, GET, OPTIONS";
    private static final String HEADERS = "Content-Type, Authorization, Idempotency-Key";

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    private final RamaCorsFilter filter = new RamaCorsFilter(ORIGINS, METHODS, HEADERS);

    @BeforeEach
    void defaultRequestMethod() {
        when(request.getMethod()).thenReturn("POST");
    }

    @Test
    void doFilter_setsCorsHeaders_andContinuesChain_forNonOptions() throws IOException, ServletException {
        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", ORIGINS);
        verify(response).setHeader("Access-Control-Allow-Methods", METHODS);
        verify(response).setHeader("Access-Control-Allow-Headers", HEADERS);
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(any(Integer.class));
    }

    @Test
    void doFilter_shortCircuitsOptions_withOk_andSkipsChain() throws IOException, ServletException {
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", ORIGINS);
        verify(response).setHeader("Access-Control-Allow-Methods", METHODS);
        verify(response).setHeader("Access-Control-Allow-Headers", HEADERS);
        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_options_isCaseInsensitive() throws IOException, ServletException {
        when(request.getMethod()).thenReturn("options");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_setsHeadersBeforeShortCircuit_soBrowserPreflightSeesThem() throws IOException, ServletException {
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        // Verifying the headers were set on the response BEFORE we returned —
        // a regression where setStatus came first would have the preflight
        // succeed but with no Allow-Headers reported back to the browser.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(response);
        inOrder.verify(response).setHeader("Access-Control-Allow-Origin", ORIGINS);
        inOrder.verify(response).setHeader("Access-Control-Allow-Methods", METHODS);
        inOrder.verify(response).setHeader("Access-Control-Allow-Headers", HEADERS);
        inOrder.verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void doFilter_othersMethodsFallThrough_evenWhenIdempotent() throws IOException, ServletException {
        // GET / PUT / DELETE all flow through. No short-circuit logic targets them.
        for (String method : new String[]{"GET", "PUT", "DELETE", "PATCH"}) {
            FilterChain freshChain = mock(FilterChain.class);
            HttpServletResponse freshResponse = mock(HttpServletResponse.class);
            when(request.getMethod()).thenReturn(method);

            filter.doFilter(request, freshResponse, freshChain);

            assertThat(method).isNotEqualTo("OPTIONS");
            verify(freshChain).doFilter(request, freshResponse);
            verify(freshResponse, never()).setStatus(any(Integer.class));
        }
    }
}
