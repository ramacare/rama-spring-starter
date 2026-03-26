package org.rama.starter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rama.starter.entity.PageableDTO;
import org.rama.starter.entity.PageableInput;
import org.rama.starter.entity.StatusCode;
import org.rama.starter.repository.BaseRepository;
import org.rama.starter.util.SanitizeUtil;
import org.springframework.data.domain.Example;
import org.springframework.data.jpa.repository.JpaRepository;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

public class GenericEntityService {
    private final ObjectMapper objectMapper;

    public GenericEntityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T, ID extends Serializable> Optional<T> createEntity(Class<T> entityClass, BaseRepository<T, ID> repository, ID entityId, Map<String, Object> input) {
        if (entityId != null && repository.existsById(entityId)) {
            throw new IllegalStateException("Duplicate entity key");
        }
        Map<String, Object> sanitized = unwrapAndSanitize(input);
        T entity = objectMapper.convertValue(sanitized, entityClass);
        entity = repository.save(entity);
        repository.flush();
        repository.refresh(entity);
        return Optional.of(entity);
    }

    public <T, ID extends Serializable> Optional<T> updateEntity(Class<T> entityClass, BaseRepository<T, ID> repository, ID entityId, Map<String, Object> input) {
        return repository.findById(entityId).map(entity -> {
            T updated;
            try {
                updated = objectMapper.updateValue(entity, unwrapAndSanitize(input));
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to update entity", ex);
            }
            updated = repository.save(updated);
            repository.flush();
            repository.refresh(updated);
            return updated;
        });
    }

    public <T, ID extends Serializable> Optional<T> softDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> repository, ID entityId) {
        return deleteEntity(entityClass, repository, entityId, "statusCode", StatusCode.terminated);
    }

    public <T, ID extends Serializable> Optional<T> hardDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> repository, ID entityId) {
        return deleteEntity(entityClass, repository, entityId, null, null);
    }

    public <T> PageableDTO<T> findEntityPageable(JpaRepository<T, ?> repository, PageableInput pageable, T filter) {
        return filter == null
                ? PageableDTO.of(repository.findAll(pageable.toPageRequest()))
                : PageableDTO.of(repository.findAll(Example.of(filter), pageable.toPageRequest()));
    }

    private <T, ID extends Serializable> Optional<T> deleteEntity(Class<T> entityClass, BaseRepository<T, ID> repository, ID entityId, String statusCodeField, Object deleteValue) {
        return repository.findById(entityId).map(entity -> {
            if (statusCodeField == null) {
                repository.delete(entity);
                return entity;
            }
            try {
                Field field = entityClass.getDeclaredField(statusCodeField);
                field.setAccessible(true);
                field.set(entity, deleteValue);
                return repository.save(entity);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to delete entity", ex);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapAndSanitize(Map<String, Object> input) {
        Object value = input.containsKey("input") ? input.get("input") : input;
        return SanitizeUtil.sanitizeMap((Map<String, Object>) value);
    }
}
