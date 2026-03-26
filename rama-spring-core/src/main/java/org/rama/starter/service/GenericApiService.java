package org.rama.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rama.starter.entity.api.Api;
import org.rama.starter.entity.api.ApiHeaderSet;
import org.rama.starter.repository.api.ApiHeaderSetRepository;
import org.rama.starter.repository.api.ApiRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericApiService {
    private final ApiRepository apiRepository;
    private final ApiHeaderSetRepository apiHeaderSetRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public GenericApiService(ApiRepository apiRepository, ApiHeaderSetRepository apiHeaderSetRepository, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.apiRepository = apiRepository;
        this.apiHeaderSetRepository = apiHeaderSetRepository;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    public <T, U> Optional<T> callApi(String apiId, U requestBody, Map<String, String> queryParams, Map<String, String> headers, boolean throwError, ParameterizedTypeReference<T> returnType) {
        Optional<Api> apiOpt = apiRepository.findById(apiId);
        if (apiOpt.isEmpty()) {
            if (throwError) {
                throw new NoSuchElementException("API not found by id: " + apiId);
            }
            return Optional.empty();
        }

        Api api = apiOpt.get();
        try {
            String uri = resolveUri(api.getSourceApiUrl(), queryParams);
            Map<String, String> finalHeaders = buildFinalHeaders(api, headers);
            String contentTypeValue = finalHeaders.containsKey("Content-Type")
                    ? finalHeaders.get("Content-Type")
                    : MediaType.APPLICATION_JSON_VALUE;
            MediaType contentType = MediaType.parseMediaType(contentTypeValue);
            HttpMethod method = HttpMethod.valueOf(api.getSourceApiMethod());

            ResponseEntity<T> response = webClientBuilder.build()
                    .method(method)
                    .uri(UriComponentsBuilder.fromUriString(uri).build(true).toUri())
                    .contentType(contentType)
                    .headers(httpHeaders -> {
                        if (!finalHeaders.isEmpty()) {
                            finalHeaders.forEach((key, value) -> {
                                if (!key.equalsIgnoreCase("Content-Type")) {
                                    httpHeaders.add(key, value);
                                }
                            });
                        }
                    })
                    .body((method == HttpMethod.GET || method == HttpMethod.DELETE || requestBody == null)
                            ? BodyInserters.empty()
                            : MediaType.APPLICATION_FORM_URLENCODED.includes(contentType)
                                    ? BodyInserters.fromValue(toFormString(requestBody))
                                    : BodyInserters.fromValue(requestBody))
                    .retrieve()
                    .toEntity(returnType)
                    .block();

            return response != null && response.hasBody() ? Optional.ofNullable(response.getBody()) : Optional.empty();
        } catch (Exception ex) {
            if (!throwError && ex instanceof WebClientResponseException responseException) {
                try {
                    T entity = responseException.getResponseBodyAs(returnType);
                    return Optional.ofNullable(entity);
                } catch (Exception ignored) {
                }
            }
            if (throwError) {
                throw new IllegalStateException("Error calling API " + apiId, ex);
            }
            return Optional.empty();
        }
    }

    public <T, U> Optional<T> callApi(String apiId, U requestBody, Map<String, String> queryParams) {
        return callApi(apiId, requestBody, queryParams, null, false, determineReturnType(apiId));
    }

    private <T> ParameterizedTypeReference<T> determineReturnType(String apiId) {
        try {
            StackTraceElement caller = Thread.currentThread().getStackTrace()[3];
            Class<?> callingClass = Class.forName(caller.getClassName());
            Method callingMethod = Arrays.stream(callingClass.getDeclaredMethods())
                    .filter(method -> method.getName().equals(caller.getMethodName()))
                    .findFirst()
                    .orElseThrow();

            org.rama.starter.annotation.ApiReturnType annotation = Arrays.stream(callingMethod.getAnnotationsByType(org.rama.starter.annotation.ApiReturnType.class))
                    .filter(apiReturnType -> apiReturnType.apiId().isEmpty() || apiReturnType.apiId().equals(apiId))
                    .findFirst()
                    .orElse(null);

            if (annotation == null) {
                return new ParameterizedTypeReference<>() {
                };
            }

            return new ParameterizedTypeReference<>() {
                @Override
                public Type getType() {
                    return new ParameterizedType() {
                        @Override
                        public Type[] getActualTypeArguments() {
                            return annotation.actualTypeArguments();
                        }

                        @Override
                        public Type getRawType() {
                            return annotation.rawType();
                        }

                        @Override
                        public Type getOwnerType() {
                            return null;
                        }
                    };
                }
            };
        } catch (Exception ex) {
            return new ParameterizedTypeReference<>() {
            };
        }
    }

    private String resolveUri(String uri, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return uri;
        }
        Map<String, String> queryMap = new HashMap<>(queryParams);
        Pattern pattern = Pattern.compile("\\{(.*?)}");
        Matcher matcher = pattern.matcher(uri);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (queryMap.containsKey(key)) {
                uri = uri.replace("{" + key + "}", queryMap.get(key));
                queryMap.remove(key);
            }
        }
        String resolvedUri = uri;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(resolvedUri);
        queryMap.forEach((key, value) -> {
            if (!resolvedUri.contains("{" + key + "}")) {
                builder.queryParam(key, value);
            }
        });
        return builder.build(true).toUriString();
    }

    public Map<String, String> buildFinalHeaders(Api api, Map<String, String> customHeaders) {
        Map<String, String> merged = new LinkedHashMap<>();
        try {
            String headerSetId = api.getApiHeaderSetId();
            if (headerSetId != null && !headerSetId.isBlank()) {
                apiHeaderSetRepository.findById(headerSetId).ifPresent(headerSet -> merged.putAll(flattenHeaderSet(headerSet)));
            }
        } catch (Exception ignored) {
        }
        if (customHeaders != null && !customHeaders.isEmpty()) {
            merged.putAll(customHeaders);
        }
        return merged;
    }

    public Map<String, String> flattenHeaderSet(ApiHeaderSet headerSet) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headerSet.getHeaders() == null) {
            return result;
        }
        for (Map<String, String> header : headerSet.getHeaders()) {
            if (header == null) {
                continue;
            }
            String key = header.get("key");
            if (key == null || key.isBlank() || !header.containsKey("value")) {
                continue;
            }
            result.put(key, header.get("value"));
        }
        return result;
    }

    private String toFormString(Object body) {
        Map<?, ?> form = body instanceof Map<?, ?> map ? map : objectMapper.convertValue(body, Map.class);
        return form.entrySet().stream()
                .map(entry -> encode(String.valueOf(entry.getKey())) + "=" + encode(String.valueOf(entry.getValue())))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String encode(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }
}
