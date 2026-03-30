package org.rama.service;

import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.net.URI;
import java.util.*;

public class VaultService {
    private static final String BASE_PATH = "secret/data";
    private final VaultTemplate vaultTemplate;

    public VaultService(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> retrieve(String path, String name) {
        VaultResponse response = vaultTemplate.read(makeSecretPath(path, name));
        if (response == null || response.getData() == null) {
            return Optional.empty();
        }
        if (response.getData().containsKey("data") && response.getData().get("data") instanceof Map<?, ?> data) {
            return Optional.of((Map<String, Object>) data);
        }
        return Optional.of(response.getData());
    }

    public void store(String path, String name, Object value) {
        Map<String, Object> data = new HashMap<>();
        if (value instanceof Map<?, ?> map) {
            data.put("data", map);
        } else {
            data.put("data", Collections.singletonMap("name", value));
        }
        vaultTemplate.write(makeSecretPath(path, name), data);
    }

    private String makeSecretPath(String path, String name) {
        String sanitizedPath = path.startsWith("/") ? path.substring(1) : path;
        String sanitizedName = name.startsWith("/") ? name.substring(1) : name;
        return URI.create(BASE_PATH + (!BASE_PATH.endsWith("/") ? "/" : ""))
                .resolve(sanitizedPath + (!sanitizedPath.endsWith("/") ? "/" : ""))
                .resolve(Objects.requireNonNullElse(sanitizedName, ""))
                .normalize()
                .toString();
    }
}
