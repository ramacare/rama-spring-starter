package org.rama.starter.repository;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.NoRepositoryBean;

import java.io.Serializable;

@NoRepositoryBean
public interface SoftDeleteRepository<T, ID extends Serializable> extends BaseRepository<T, ID> {
    default Specification<T> isActive() {
        return (root, query, cb) -> cb.equal(root.get("statusCode"), "active");
    }

    default Specification<T> withoutTerminated() {
        return (root, query, cb) -> cb.notEqual(root.get("statusCode"), "terminated");
    }
}
