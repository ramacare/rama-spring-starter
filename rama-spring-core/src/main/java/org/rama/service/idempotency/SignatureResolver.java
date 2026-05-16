package org.rama.service.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.rama.service.environment.EnvironmentService;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
public class SignatureResolver {

    private final EnvironmentService environmentService;
    private final IdempotencyProperties properties;
    private final Clock clock;

    /**
     * Compute the dedup signature for a method invocation.
     *
     * Header path: SHA-256("h:" + Idempotency-Key + "|" + username)
     * Fallback:    SHA-256("t:" + floor(now,1s) + "|" + username + "|" + canonicalJson(args))
     *
     * Output is always 64 hex chars — fits the {@code system_request_dedup.id varchar(64)} column.
     */
    public String resolve(Object[] args) {
        String username = nullSafe(environmentService.getCurrentUsername());
        String header = readHeader(properties.getHeaderName());

        String raw;
        if (header != null && !header.isBlank()) {
            raw = "h:" + header + "|" + username;
        } else {
            long secondBucket = Instant.now(clock).getEpochSecond();
            String argsJson = CanonicalJson.stringify(args);
            raw = "t:" + secondBucket + "|" + username + "|" + argsJson;
        }
        return sha256Hex(raw);
    }

    private static String readHeader(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) return null;
        HttpServletRequest req = sra.getRequest();
        return req.getHeader(name);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
