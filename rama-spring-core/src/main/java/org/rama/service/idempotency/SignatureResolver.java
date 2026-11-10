package org.rama.service.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.rama.service.environment.EnvironmentService;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.StringJoiner;

@RequiredArgsConstructor
public class SignatureResolver {

    private final EnvironmentService environmentService;
    private final IdempotencyProperties properties;

    /**
     * The dedup signature for one invocation, and which path produced it.
     *
     * @param signature 64 hex chars — fits the {@code system_request_dedup.id varchar(64)} column
     * @param keyed     {@code true} when the caller sent an idempotency header; the row is then
     *                  held for the method's {@code ttl}. {@code false} for the content
     *                  signature, held for its {@code fallbackWindow}.
     */
    public record Resolution(String signature, boolean keyed) {}

    /**
     * Compute the dedup signature for a method invocation.
     *
     * Header path: SHA-256("h:" + Idempotency-Key + "|" + username)
     * Fallback:    SHA-256("c:" + methodKey(method) + "|" + username + "|" + canonicalJson(args))
     *
     * The fallback carries no time component. It used to start with {@code floor(now, 1s)},
     * which deduplicated only calls that landed in the same wall-clock second; the window now
     * comes from the row's {@code expires_at}. The method is part of it so two mutations
     * called with equal arguments never share a row. See starter#67.
     */
    public Resolution resolve(Method method, Object[] args) {
        String username = nullSafe(environmentService.getCurrentUsername());
        String header = readHeader(properties.getHeaderName());

        if (header != null && !header.isBlank()) {
            return new Resolution(sha256Hex("h:" + header + "|" + username), true);
        }
        String argsJson = CanonicalJson.stringify(args, properties.getVolatileFields());
        return new Resolution(sha256Hex("c:" + methodKey(method) + "|" + username + "|" + argsJson), false);
    }

    /**
     * {@code declaring.Class#name(paramType,...)}. Parameter types keep overloads apart;
     * modifiers and the return type are left out so they cannot shift the signature.
     */
    public static String methodKey(Method method) {
        StringJoiner params = new StringJoiner(",", "(", ")");
        for (Class<?> type : method.getParameterTypes()) {
            params.add(type.getTypeName());
        }
        return method.getDeclaringClass().getName() + "#" + method.getName() + params;
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
