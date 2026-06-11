package org.rama.controller.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.security.ApiKeyService;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ApiKeyAdminControllerTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Test
    void create_delegatesToService_andReturnsCreatedKey() {
        ApiKeyAdminController controller = new ApiKeyAdminController(apiKeyService);
        OffsetDateTime expires = OffsetDateTime.parse("2030-01-01T00:00:00Z");
        var req = new ApiKeyAdminController.CreateApiKeyRequest(
                "odoo-sync", "svc-odoo", List.of("ROLE_API_USER"), expires);
        var expected = new ApiKeyService.CreatedApiKeyResponse(7L, "odoo-sync", "rawsecret");
        when(apiKeyService.createKey("odoo-sync", "svc-odoo", List.of("ROLE_API_USER"), expires))
                .thenReturn(expected);

        var result = controller.create(req);

        assertThat(result).isEqualTo(expected);
        verify(apiKeyService).createKey("odoo-sync", "svc-odoo", List.of("ROLE_API_USER"), expires);
    }

    @Test
    void revoke_delegatesToService() {
        ApiKeyAdminController controller = new ApiKeyAdminController(apiKeyService);

        controller.revoke(42L);

        verify(apiKeyService).revoke(42L);
    }
}
