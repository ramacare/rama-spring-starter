package org.rama.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultServiceTest {

    @Mock
    private VaultTemplate vaultTemplate;

    @InjectMocks
    private VaultService vaultService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> dataCaptor;

    @Test
    void retrieve_shouldReturnDataFromNestedMap() {
        Map<String, Object> nestedData = Map.of("username", "admin", "password", "secret");
        VaultResponse response = new VaultResponse();
        response.setData(Map.of("data", nestedData));

        when(vaultTemplate.read(any())).thenReturn(response);

        Optional<Map<String, Object>> result = vaultService.retrieve("app", "credentials");

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("username", "admin");
        assertThat(result.get()).containsEntry("password", "secret");
    }

    @Test
    void retrieve_shouldReturnEmptyWhenResponseNull() {
        when(vaultTemplate.read(any())).thenReturn(null);

        Optional<Map<String, Object>> result = vaultService.retrieve("app", "missing");

        assertThat(result).isEmpty();
    }

    @Test
    void retrieve_shouldReturnDirectDataWhenNoNestedMap() {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of("key1", "value1", "key2", "value2"));

        when(vaultTemplate.read(any())).thenReturn(response);

        Optional<Map<String, Object>> result = vaultService.retrieve("app", "simple");

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("key1", "value1");
        assertThat(result.get()).containsEntry("key2", "value2");
    }

    @SuppressWarnings("unchecked")
    @Test
    void store_shouldWrapMapValueInDataKey() {
        Map<String, Object> value = Map.of("username", "admin");

        vaultService.store("app", "credentials", value);

        verify(vaultTemplate).write(any(), dataCaptor.capture());
        Map<String, Object> written = dataCaptor.getValue();
        assertThat(written).containsKey("data");
        assertThat((Map<String, Object>) written.get("data")).containsEntry("username", "admin");
    }

    @SuppressWarnings("unchecked")
    @Test
    void store_shouldWrapNonMapValueInNameKey() {
        vaultService.store("app", "token", "my-token-value");

        verify(vaultTemplate).write(any(), dataCaptor.capture());
        Map<String, Object> written = dataCaptor.getValue();
        assertThat(written).containsKey("data");
        assertThat((Map<String, Object>) written.get("data")).containsEntry("name", "my-token-value");
    }

    @Test
    void makeSecretPath_shouldConstructCorrectPath() {
        vaultService.retrieve("myApp", "dbCreds");

        verify(vaultTemplate).read(eq("secret/data/myApp/dbCreds"));
    }

    @Test
    void makeSecretPath_shouldHandleLeadingSlashes() {
        vaultService.retrieve("/myApp/", "/dbCreds");

        verify(vaultTemplate).read(eq("secret/data/myApp/dbCreds"));
    }
}
