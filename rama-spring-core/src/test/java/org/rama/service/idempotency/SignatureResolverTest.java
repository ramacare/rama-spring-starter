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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class SignatureResolverTest {

    @Mock
    EnvironmentService environmentService;

    private final IdempotencyProperties properties = new IdempotencyProperties();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void headerPath_producesSha256OfHeaderAndUsername() {
        given(environmentService.getCurrentUsername()).willReturn("alice");
        setRequestWithHeader("Idempotency-Key", "client-uuid-42");

        SignatureResolver resolver = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:00Z"));

        String sig = resolver.resolve(new Object[]{"anything"});

        assertThat(sig).hasSize(64);
        assertThat(sig).isEqualTo(sha256Hex("h:client-uuid-42|alice"));
    }

    @Test
    void fallbackPath_includesSecondBucketUsernameAndArgs() {
        given(environmentService.getCurrentUsername()).willReturn("bob");
        Clock c = fixedClock("2026-05-15T00:00:42.500Z");

        SignatureResolver resolver = new SignatureResolver(environmentService, properties, c);
        Object[] args = {42, "make-payment"};

        String sig = resolver.resolve(args);

        long bucket = Instant.parse("2026-05-15T00:00:42.500Z").getEpochSecond();
        String expected = sha256Hex("t:" + bucket + "|bob|" + CanonicalJson.stringify(args));
        assertThat(sig).isEqualTo(expected);
    }

    @Test
    void sameInputsProduceSameSignature() {
        given(environmentService.getCurrentUsername()).willReturn("carol");
        Clock c = fixedClock("2026-05-15T12:34:56Z");

        SignatureResolver resolver = new SignatureResolver(environmentService, properties, c);

        String first = resolver.resolve(new Object[]{"x", 1});
        String second = resolver.resolve(new Object[]{"x", 1});
        assertThat(first).isEqualTo(second);
    }

    @Test
    void blankHeaderFallsBackToTimeBucket() {
        given(environmentService.getCurrentUsername()).willReturn("dave");
        setRequestWithHeader("Idempotency-Key", "   ");

        Clock c = fixedClock("2026-05-15T00:00:42Z");
        SignatureResolver resolver = new SignatureResolver(environmentService, properties, c);

        String sig = resolver.resolve(new Object[]{"args"});

        long bucket = Instant.parse("2026-05-15T00:00:42Z").getEpochSecond();
        String expected = sha256Hex("t:" + bucket + "|dave|" + CanonicalJson.stringify(new Object[]{"args"}));
        assertThat(sig).isEqualTo(expected);
    }

    @Test
    void timeBucketBoundary_secondAdvance_changesSignature() {
        given(environmentService.getCurrentUsername()).willReturn("eve");

        SignatureResolver atTPoint999 = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:00.999Z"));
        SignatureResolver atTPlus1Point000 = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:01.000Z"));

        Object[] args = {"same", "args"};
        String first = atTPoint999.resolve(args);
        String second = atTPlus1Point000.resolve(args);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void withinSameSecond_signaturesCollapse() {
        given(environmentService.getCurrentUsername()).willReturn("frank");

        SignatureResolver atTPoint100 = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:00.100Z"));
        SignatureResolver atTPoint900 = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:00.900Z"));

        Object[] args = {"same"};
        assertThat(atTPoint100.resolve(args)).isEqualTo(atTPoint900.resolve(args));
    }

    @Test
    void nullUsernameTreatsAsEmptyString() {
        given(environmentService.getCurrentUsername()).willReturn(null);
        setRequestWithHeader("Idempotency-Key", "key1");

        SignatureResolver resolver = new SignatureResolver(
                environmentService, properties, fixedClock("2026-05-15T00:00:00Z"));

        String sig = resolver.resolve(new Object[]{});
        assertThat(sig).isEqualTo(sha256Hex("h:key1|"));
    }

    private static void setRequestWithHeader(String name, String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(name, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Clock fixedClock(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
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
