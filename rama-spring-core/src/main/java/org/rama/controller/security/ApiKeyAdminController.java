package org.rama.controller.security;

import lombok.RequiredArgsConstructor;
import org.rama.security.ApiKeyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Admin REST surface for API keys. Disabled by default; activated together with
 * {@link ApiKeyService} by {@code rama.api-key.enabled=true}. Picked up by consumers that
 * component-scan {@code org.rama}.
 */
@RestController
@ConditionalOnProperty(prefix = "rama.api-key", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@RequestMapping("/api/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;

    public record CreateApiKeyRequest(
            String name,
            String username,
            List<String> roles,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime expiresAt
    ) {}

    @PostMapping
    public ApiKeyService.CreatedApiKeyResponse create(@RequestBody CreateApiKeyRequest req) {
        return apiKeyService.createKey(req.name(), req.username(), req.roles(), req.expiresAt());
    }

    @PostMapping("/{id}/revoke")
    public void revoke(@PathVariable Long id) {
        apiKeyService.revoke(id);
    }
}
