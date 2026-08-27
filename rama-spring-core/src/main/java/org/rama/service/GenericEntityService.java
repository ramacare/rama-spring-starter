package org.rama.service;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.Wildcard;
import com.querydsl.jpa.impl.JPAQuery;
import graphql.GraphQLException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

    /**
     * Used only to take the pessimistic lock in
     * {@link #updateEntity(Class, BaseRepository, Serializable, Map)}. Every other path
     * goes through the repository. See starter#41.
     */
    private final EntityManager entityManager;

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

    /*
     * The entityIdKey overloads below delegate to the ID-taking method on `this`, which bypasses
     * the CGLIB proxy -- so @Transactional on the target has no effect and each repository call
     * runs in its own transaction. createEntity then saves in one transaction and refreshes in
     * another, against a detached entity ("Given entity is not associated with the persistence
     * context"), which broke every create mutation in an application with open-in-view disabled.
     * Annotating the entry point itself puts the whole delegation in one transaction. See starter#36.
     */
    @Transactional
    public <T, ID extends Serializable> Optional<T> createEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return createEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey, true), entityInput);
    }

    /**
     * Reads the row under {@link LockModeType#PESSIMISTIC_WRITE} so the {@code updatedAt}
     * conflict check below is actually serialized.
     *
     * <p>The check is a hand-rolled optimistic-concurrency guard: the client echoes back
     * the {@code updatedAt} it saw, and
     * {@code GlobalAuditablePreUpdateListener} advances that column on every write. Read
     * without a lock, two overlapping updaters both observe the same {@code updatedAt},
     * both pass the check, and one write is silently lost — the row lock taken at the
     * {@code UPDATE} serializes the writes, but it arrives long after the second caller
     * made its decision on a stale read. Locking at the read closes that window: the
     * second caller blocks until the first commits and then observes the advanced
     * timestamp. See starter#41.
     *
     * <p>Deliberately {@code EntityManager.find} rather than a repository query: it uses
     * the entity's real {@code @Id} whatever it is named, needs no change to
     * {@link BaseRepository}, and leaves every read path lock-free.
     * {@code SoftDeleteRepository} does not override {@code findById}, so no soft-delete
     * filtering is bypassed by the switch.
     *
     * <p>One limit worth knowing: if a caller already loaded this entity earlier in the
     * same transaction, {@code find} returns the instance from the persistence context
     * and takes the lock without re-reading, so the check is only as fresh as that
     * earlier read. The mutation entry points start their own transaction, so this is
     * not the normal path.
     *
     * <p>The delete family is intentionally left unlocked — see
     * {@link #deleteEntity(Class, BaseRepository, Serializable, String, Object)}.
     */
    @Transactional
    public <T, ID extends Serializable> Optional<T> updateEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId, Map<String, Object> entityInput) {
        Optional<T> entity = Optional.ofNullable(entityManager.find(entityClass, entityId, LockModeType.PESSIMISTIC_WRITE));
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

    @Transactional
    public <T, ID extends Serializable> Optional<T> updateEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return updateEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey), entityInput);
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> softDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return softDeleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey));
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> softDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId) {
        return deleteEntity(entityClass, entityRepository, entityId, "statusCode", StatusCode.terminated);
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> hardDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey) {
        return hardDeleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey));
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> hardDeleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, ID entityId) {
        return deleteEntity(entityClass, entityRepository, entityId, null, null);
    }

    @Transactional
    public <T, ID extends Serializable> Optional<T> deleteEntity(Class<T> entityClass, BaseRepository<T, ID> entityRepository, Map<String, Object> entityInput, String entityIdKey, String statusCodeField, Object deleteValue) {
        return deleteEntity(entityClass, entityRepository, extractEntityKey(entityInput, entityIdKey), statusCodeField, deleteValue);
    }

    /**
     * Reads without a lock, unlike
     * {@link #updateEntity(Class, BaseRepository, Serializable, Map)}.
     *
     * <p>starter#41 left this open to decide during implementation. Deliberately
     * unlocked: every delete variant funnels here, and none of them makes a
     * read-derived decision that is written back — the read fetches the row, then either
     * deletes it or sets one status field to a caller-supplied constant. There is no
     * conflict check for a stale read to defeat, so a lock would buy no correctness and
     * would widen the deadlock surface on a path every consumer uses. A delete racing an
     * update stays last-write-wins, which is the usual semantic for deletes.
     */
    @Transactional
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
