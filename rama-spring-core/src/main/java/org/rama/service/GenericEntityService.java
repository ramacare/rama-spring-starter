package org.rama.service;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.Wildcard;
import com.querydsl.jpa.impl.JPAQuery;
import graphql.GraphQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.rama.entity.*;
import org.rama.repository.BaseRepository;
import org.rama.util.ExceptionUtil;
import org.rama.util.SanitizeUtil;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@SuppressWarnings({"unchecked"})
@Slf4j
@RequiredArgsConstructor
public class GenericEntityService {
    private final JsonMapper mapper;

    @Transactional
    public <T, ID extends Serializable> Optional<T> createEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId, Map<String, Object> entityInput) {
        T entity;
        try {
            if (entityId != null && entityRepository.existsById(entityId)) {
                throw new Exception("Duplicate entity key");
            }
            if (entityInput.containsKey("input")) {
                entityInput = (Map<String, Object>) entityInput.get("input");
            }

            entityInput = SanitizeUtil.sanitizeMap(entityInput);

            entity = mapper.convertValue(entityInput, entityClass);
            if (entity instanceof Auditable auditable) {
                auditable.setUserstampField(new UserstampField());
                auditable.setTimestampField(new TimestampField());
            }
            entity = entityRepository.save(entity);

            entityRepository.flush();
            entityRepository.refresh(entity);

            log.debug("Created {} with ID {}", entityClass.getName(), entityId);
        } catch (Exception e) {
            log.error("Error creating {}: {}", entityClass.getName(), ExceptionUtil.getDeepestExceptionMessage(e));
            throw new GraphQLException(ExceptionUtil.getDeepestExceptionMessage(e));
        }

        return Optional.of(entity);
    }

    public <T, ID extends Serializable> Optional<T> createEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return createEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey, true), entityInput);
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> updateEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId, Map<String, Object> entityInput) {
        Optional<T> entity = entityRepository.findById(entityId);
        T updatedEntity = null;
        if (entity.isPresent()) {
            try {
                if (entityInput.containsKey("input")) {
                    entityInput = (Map<String, Object>) entityInput.get("input");
                }

                entityInput = SanitizeUtil.sanitizeMap(entityInput);

                if (entity.get() instanceof Auditable auditable) {
                    TimestampField currentTimestampField = auditable.getTimestampField();
                    if (currentTimestampField != null) {
                        OffsetDateTime currentUpdatedAt = currentTimestampField.getUpdatedAt();

                        if (entityInput.containsKey("timestampField") && entityInput.get("timestampField") instanceof Map<?, ?> timestampFieldMap) {
                            String recordTimestampString = null;
                            if (timestampFieldMap.containsKey("updatedAt") && !String.valueOf(timestampFieldMap.get("updatedAt")).isEmpty()) {
                                recordTimestampString = String.valueOf(timestampFieldMap.get("updatedAt"));
                            }
                            if (recordTimestampString != null) {
                                OffsetDateTime recordTimestamp = OffsetDateTime.parse(recordTimestampString);
                                if (currentUpdatedAt != null && !currentUpdatedAt.equals(recordTimestamp)) {
                                    throw new GraphQLException("Conflict detected: The updatedAt value does not match the current entity state.");
                                }
                            }
                        }

                        entityInput.put("timestampField", currentTimestampField);
                    }

                    UserstampField currentUserstampField = auditable.getUserstampField();
                    if (currentUserstampField != null) {
                        entityInput.put("userstampField", currentUserstampField);
                    }
                }

                log.debug("Update {} ID {}", entityClass.getName(), entityId);
                updatedEntity = mapper.updateValue(entity.get(), entityInput);
                updatedEntity = entityRepository.save(updatedEntity);

                entityRepository.flush();
                entityRepository.refresh(updatedEntity);
            } catch (Exception e) {
                log.error("Error updating {} ID {}: {}", entityClass.getName(), entityId, ExceptionUtil.getDeepestExceptionMessage(e));
                throw new GraphQLException(ExceptionUtil.getDeepestExceptionMessage(e));
            }
        }

        return Optional.ofNullable(updatedEntity);
    }

    public <T, ID extends Serializable> Optional<T> updateEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return updateEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey), entityInput);
    }

    public <T, ID extends Serializable> Optional<T> softDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return softDeleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey));
    }

    public <T, ID extends Serializable> Optional<T> softDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId) {
        return deleteEntity(entityClass, entityRepository, entityId, "statusCode", StatusCode.terminated);
    }

    public <T, ID extends Serializable> Optional<T> hardDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return hardDeleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey));
    }

    public <T, ID extends Serializable> Optional<T> hardDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId) {
        return deleteEntity(entityClass, entityRepository, entityId, null, null);
    }

    public <T, ID extends Serializable> Optional<T> deleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey, String statusCodeField, Object deleteValue) {
        return deleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey), statusCodeField, deleteValue);
    }

    public <T, ID extends Serializable> Optional<T> deleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId, String statusCodeField, Object deleteValue) {
        Optional<T> entityOptional = entityRepository.findById(entityId);

        if (entityOptional.isEmpty()) {
            log.warn("Entity {} with ID {} not found for deletion", entityClass.getName(), entityId);
            throw new GraphQLException("Entity not found");
        }

        T entity = entityOptional.get();

        try {
            if (statusCodeField == null) {
                entityRepository.delete(entity);
                log.debug("Physically deleted entity {} with ID {}", entityClass.getName(), entityId);
            } else {
                Field field = entityClass.getDeclaredField(statusCodeField);
                field.setAccessible(true);
                field.set(entity, deleteValue);
                entityRepository.save(entity);
                log.debug("Soft deleted entity {} with ID {}, set {} to {}", entityClass.getName(), entityId, statusCodeField, deleteValue);
            }

            return Optional.of(entity);
        } catch (NoSuchFieldException e) {
            log.error("Field {} does not exist in class {}", statusCodeField, entityClass.getName());
            throw new GraphQLException("Field " + statusCodeField + " does not exist");
        } catch (IllegalAccessException e) {
            log.error("Unable to access field {} in class {}", statusCodeField, entityClass.getName());
            throw new GraphQLException("Unable to access field " + statusCodeField);
        } catch (Exception e) {
            log.error("Error deleting entity {} with ID {}", entityClass.getName(), entityId);
            throw new GraphQLException("Error deleting entity: " + e.getMessage());
        }
    }

    private <ID extends Serializable> ID extractEntityKey(Map<String, Object> entityInput, String entityIdKey) {
        return extractEntityKey(entityInput, entityIdKey, false);
    }

    private <ID extends Serializable> ID extractEntityKey(Map<String, Object> entityInput, String entityIdKey, Boolean nullable) {
        ID entityId = null;
        try {
            if (entityInput.containsKey("input")) {
                entityInput = (Map<String, Object>) entityInput.get("input");
            }
            if (!entityIdKey.isEmpty() && entityInput.containsKey(entityIdKey)) {
                entityId = (ID) entityInput.get(entityIdKey);
            }
        } catch (Exception e) {
            log.error("Error getting entity key '{}': {}", entityIdKey, e.getMessage());
            log.error(e.getMessage());
            throw new GraphQLException(e.getMessage());
        }

        if (entityId == null && !nullable) {
            log.error("No required entity key");
            throw new GraphQLException("No required entity key");
        }

        return entityId;
    }

    public static <T> PageableDTO<T> findEntityPageable(JpaRepository<T, ?> entityRepository, PageableInput pageable, T filter) {
        Page<T> result = (filter == null) ? entityRepository.findAll(pageable.toPageRequest()) : entityRepository.findAll(Example.of(filter), pageable.toPageRequest());
        return PageableDTO.of(result);
    }

    public static <T> PageableDTO<T> findEntityPageable(QuerydslPredicateExecutor<T> entityRepository, PageableInput pageable, @NotNull Predicate predicate) {
        Page<T> result = entityRepository.findAll(predicate, pageable.toPageRequest());
        return PageableDTO.of(result);
    }

    public static <T> PageableDTO<T> findEntityPageable(JpaRepository<T, ?> entityRepository, PageableInput pageable) {
        return findEntityPageable(entityRepository, pageable, null);
    }

    public static <T, S extends EntityPathBase<T>> PageableDTO<T> findEntityPageable(S pathBase, JPAQuery<T> jpaQuery, PageableInput pageable) {
        Long count = jpaQuery.clone().select(Wildcard.count).fetchOne();
        long softCount = (count == null || count == 0) ? 0 : count;
        int totalPages = (int) ((softCount + pageable.toPageRequest().getPageSize() - 1) / pageable.toPageRequest().getPageSize());

        if (pageable.getSortBy() != null && !pageable.getSortBy().isEmpty()) {
            PathBuilder<T> pathBuilder = new PathBuilder<>(pathBase.getType(), pathBase.getMetadata());

            pageable.getSortBy().forEach(sortCriteria -> {
                OrderSpecifier<?> orderSpecifier = new OrderSpecifier<>(Order.valueOf(sortCriteria.getOrder().toUpperCase()), pathBuilder.getString(sortCriteria.getKey()));
                jpaQuery.orderBy(orderSpecifier);
            });
        }
        List<T> result = jpaQuery.offset(pageable.toPageRequest().getOffset()).limit(pageable.toPageRequest().getPageSize()).fetch();

        return PageableDTO.of(PageableMeta.of(pageable.getPage(), pageable.getPerPage(), pageable.getSortBy(), totalPages, softCount), result);
    }

    public static <T, R, K> Map<T, List<R>> batchMappingRelation(List<T> parents, Function<T, K> parentKeyMapper, Function<R, K> childKeyMapper, Function<Set<K>, List<R>> itemsFinder) {
        Set<K> groupKeySet = parents.stream().map(parentKeyMapper).collect(Collectors.toSet());
        return batchMappingRelation(parents, parentKeyMapper, childKeyMapper, itemsFinder.apply(groupKeySet));
    }

    public static <T, R, K> Map<T, List<R>> batchMappingRelation(List<T> parents, Function<T, K> parentKeyMapper, Function<R, K> childKeyMapper, List<R> itemList) {
        Map<K, T> keyGroupMap = parents.stream().collect(Collectors.toMap(parentKeyMapper, Function.identity()));
        return itemList.stream().collect(Collectors.groupingBy(item -> keyGroupMap.get(childKeyMapper.apply(item))));
    }

    public static <T, R, K> Map<T, R> batchMappingRelationSingle(List<T> parents, Function<T, K> parentKeyMapper, Function<R, K> childKeyMapper, Function<Set<K>, List<R>> itemsFinder) {
        Set<K> groupKeySet = parents.stream().map(parentKeyMapper).filter(Objects::nonNull).collect(Collectors.toSet());
        return batchMappingRelationSingle(parents, parentKeyMapper, childKeyMapper, itemsFinder.apply(groupKeySet));
    }

    public static <T, R, K> Map<T, R> batchMappingRelationSingle(List<T> parents, Function<T, K> parentKeyMapper, Function<R, K> childKeyMapper, List<R> itemList) {
        Map<K, R> childByKey = new HashMap<>();
        for (R item : itemList) {
            childByKey.putIfAbsent(childKeyMapper.apply(item), item);
        }

        Map<T, R> resultMap = new HashMap<>();
        for (T parent : parents) {
            K parentKey = parentKeyMapper.apply(parent);
            resultMap.put(parent, parentKey != null ? childByKey.get(parentKey) : null);
        }
        return resultMap;
    }
}
