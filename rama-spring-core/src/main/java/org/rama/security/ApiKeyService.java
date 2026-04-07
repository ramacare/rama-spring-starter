package org.rama.security;

import lombok.Getter;
import org.rama.entity.security.ApiKey;
import org.rama.repository.security.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public class ApiKeyService {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final ApiKeyRepository apiKeyRepository;
    @Getter
    private final String keyPrefix;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, String applicationName) {
        this.apiKeyRepository = apiKeyRepository;
        // Derive prefix from application name: "his-service" → "his-service_"
        this.keyPrefix = (applicationName != null && !applicationName.isBlank())
                ? applicationName.replaceAll("[^a-zA-Z0-9-]", "") + "_"
                : "";
    }

    public record ApiKeyAuthResult(boolean valid, String username, List<String> roles) {}

    public record CreatedApiKeyResponse(Long id, String name, String rawKey) {}

    @Transactional(readOnly = true)
    public ApiKeyAuthResult authenticate(String rawKey) {
        String hash = sha256Hex(rawKey);
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(hash);

        if (found.isEmpty()) return new ApiKeyAuthResult(false, null, List.of());

        ApiKey key = found.get();
        if (!key.isEnabled()) return new ApiKeyAuthResult(false, null, List.of());
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return new ApiKeyAuthResult(false, null, List.of());
        }

        List<String> roles = key.getRoles() == null ? List.of() : key.getRoles();
        return new ApiKeyAuthResult(true, key.getUsername(), roles);
    }

    @Transactional
    public void markUsed(String rawKey) {
        String hash = sha256Hex(rawKey);
        apiKeyRepository.findByKeyHash(hash).ifPresent(k -> {
            k.setLastUsedAt(OffsetDateTime.now());
            apiKeyRepository.save(k);
        });
    }

    /**
     * Create a new API key. The raw key is returned ONCE — only the hash is stored.
     * <p>
     * Key format: {@code {appName}_{base62random}} e.g. {@code his-service_7kXp2mNqR9sLwYz...}
     */
    @Transactional
    public CreatedApiKeyResponse createKey(String name,
                                           String username,
                                           List<String> roles,
                                           OffsetDateTime expiresAt) {
        String raw = keyPrefix + generateBase62(43);
        String hash = sha256Hex(raw);

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .name(name)
                .keyHash(hash)
                .username(username)
                .roles(roles)
                .enabled(true)
                .expiresAt(expiresAt)
                .build());

        return new CreatedApiKeyResponse(saved.getId(), saved.getName(), raw);
    }

    @Transactional
    public void revoke(Long id) {
        apiKeyRepository.findById(id).ifPresent(k -> {
            k.setEnabled(false);
            apiKeyRepository.save(k);
        });
    }

    private static String generateBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(SECURE_RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
