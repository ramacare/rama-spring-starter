package org.rama.service.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.service.environment.EnvironmentService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class SignatureResolverTest {

    @Mock
    EnvironmentService environmentService;

    private final IdempotencyProperties properties = new IdempotencyProperties();

    private static final Method PAY = method("pay");
    private static final Method REFUND = method("refund");

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void headerPath_producesSha256OfHeaderAndUsername() {
        given(environmentService.getCurrentUsername()).willReturn("alice");
        setRequestWithHeader("Idempotency-Key", "client-uuid-42");

        SignatureResolver.Resolution r = resolver().resolve(PAY, new Object[]{"anything"});

        assertThat(r.keyed()).isTrue();
        assertThat(r.signature()).hasSize(64);
        assertThat(r.signature()).isEqualTo(sha256Hex("h:client-uuid-42|alice"));
    }

    /**
     * starter#67: the header-less signature used to start with {@code floor(now, 1s)}, so
     * two identical calls deduplicated only when they fell in the same wall-clock second.
     * It now carries no time component at all — the window comes from the row's
     * {@code expires_at} instead.
     */
    @Test
    @Tag("issue-67")
    @Tag("TC-67-01")
    void fallbackPath_hashesMethodUsernameAndArgs_withNoTimeComponent() {
        given(environmentService.getCurrentUsername()).willReturn("bob");
        Object[] args = {42, "make-payment"};

        SignatureResolver.Resolution r = resolver().resolve(PAY, args);

        assertThat(r.keyed()).isFalse();
        assertThat(r.signature()).isEqualTo(
                sha256Hex("c:" + SignatureResolver.methodKey(PAY) + "|bob|" + CanonicalJson.stringify(args)));
    }

    /** starter#67: the method was stored on the row but never hashed, so equal args collided across mutations. */
    @Test
    @Tag("issue-67")
    @Tag("TC-67-02")
    void fallbackPath_differentMethodsWithEqualArgs_produceDifferentSignatures() {
        given(environmentService.getCurrentUsername()).willReturn("carol");
        Object[] args = {42L, "INV-1"};

        assertThat(resolver().resolve(PAY, args).signature())
                .isNotEqualTo(resolver().resolve(REFUND, args).signature());
    }

    @Test
    void sameMethodAndInputsProduceSameSignature() {
        given(environmentService.getCurrentUsername()).willReturn("carol");

        String first = resolver().resolve(PAY, new Object[]{"x", 1}).signature();
        String second = resolver().resolve(PAY, new Object[]{"x", 1}).signature();
        assertThat(first).isEqualTo(second);
    }

    @Test
    @Tag("issue-67")
    @Tag("TC-67-03")
    void blankHeaderFallsBackToContentSignature() {
        given(environmentService.getCurrentUsername()).willReturn("dave");
        setRequestWithHeader("Idempotency-Key", "   ");

        SignatureResolver.Resolution r = resolver().resolve(PAY, new Object[]{"args"});

        assertThat(r.keyed()).isFalse();
        assertThat(r.signature()).isEqualTo(sha256Hex(
                "c:" + SignatureResolver.methodKey(PAY) + "|dave|" + CanonicalJson.stringify(new Object[]{"args"})));
    }

    /**
     * starter#67: {@code requestDatetime}/{@code traceId} were stripped from POJOs only, never
     * from the {@code Map<String, Object> input} every Rama mutation takes. Configured names
     * ride along.
     */
    @Test
    @Tag("issue-67")
    @Tag("TC-67-04")
    void fallbackPath_mapInputDifferingOnlyByVolatileKeys_producesSameSignature() {
        given(environmentService.getCurrentUsername()).willReturn("erin");
        properties.setVolatileFields(Set.of("clientStamp"));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "x");
        first.put("requestDatetime", "2026-11-10T10:00:00Z");
        first.put("traceId", "t-1");
        first.put("clientStamp", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("name", "x");
        second.put("requestDatetime", "2026-11-10T10:00:03Z");
        second.put("traceId", "t-2");
        second.put("clientStamp", 2);

        assertThat(resolver().resolve(PAY, new Object[]{first}).signature())
                .isEqualTo(resolver().resolve(PAY, new Object[]{second}).signature());
    }

    @Test
    @Tag("issue-67")
    @Tag("TC-67-05")
    void methodKey_namesDeclaringClassMethodAndParameterTypes() {
        assertThat(SignatureResolver.methodKey(PAY))
                .isEqualTo(Target.class.getName() + "#pay(long,java.lang.String)");
    }

    @Test
    void nullUsernameTreatsAsEmptyString() {
        given(environmentService.getCurrentUsername()).willReturn(null);
        setRequestWithHeader("Idempotency-Key", "key1");

        String sig = resolver().resolve(PAY, new Object[]{}).signature();
        assertThat(sig).isEqualTo(sha256Hex("h:key1|"));
    }

    private SignatureResolver resolver() {
        return new SignatureResolver(environmentService, properties);
    }

    private static void setRequestWithHeader(String name, String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(name, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Method method(String name) {
        try {
            return Target.class.getMethod(name, long.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    public static class Target {
        public String pay(long amount, String invoiceId) { return "paid"; }
        public String refund(long amount, String invoiceId) { return "refunded"; }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
