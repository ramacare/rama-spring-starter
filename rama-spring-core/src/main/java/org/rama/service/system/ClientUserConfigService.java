package org.rama.service.system;

import org.rama.entity.system.ClientUserConfig;
import org.rama.repository.system.ClientUserConfigRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;

public class ClientUserConfigService {
    private final ClientUserConfigRepository clientUserConfigRepository;

    public ClientUserConfigService(ClientUserConfigRepository clientUserConfigRepository) {
        this.clientUserConfigRepository = clientUserConfigRepository;
    }

    /**
     * Returns the configuration for a client OS user, registering an empty one on first sight so
     * callers never have to handle a missing row on first login.
     */
    @Transactional
    public ClientUserConfig retrieveOrRegister(String clientUsername) {
        if (clientUsername == null || clientUsername.isBlank()) {
            throw new IllegalArgumentException("clientUsername is required");
        }

        ClientUserConfig clientUserConfig = clientUserConfigRepository.findByClientUsername(clientUsername)
                .orElseGet(() -> {
                    ClientUserConfig created = new ClientUserConfig();
                    created.setClientUsername(clientUsername);
                    created.setConfiguration(new HashMap<>());
                    return created;
                });
        clientUserConfig.setLastSeenDatetime(OffsetDateTime.now());
        return clientUserConfigRepository.save(clientUserConfig);
    }
}
