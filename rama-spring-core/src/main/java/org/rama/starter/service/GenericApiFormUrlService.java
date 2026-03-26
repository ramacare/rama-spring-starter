package org.rama.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rama.starter.repository.api.ApiHeaderSetRepository;
import org.rama.starter.repository.api.ApiRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;

public class GenericApiFormUrlService {
    private final GenericApiService delegate;

    public GenericApiFormUrlService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.delegate = new GenericApiService(apiRepository, apiHeaderSetRepository, webClientBuilder, objectMapper);
    }

    public <T, U> Optional<T> callApi(String apiId, U requestBody, Map<String, String> queryParams, Map<String, String> customHeaders, boolean throwError, ParameterizedTypeReference<T> returnType) {
        return delegate.callApi(apiId, requestBody, queryParams, customHeaders, throwError, returnType);
    }
}
