package org.rama.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.NoRepositoryBean;

import java.io.Serializable;
import java.util.List;

@NoRepositoryBean
public interface SoftDeleteRepository<T, ID extends Serializable> extends BaseRepository<T, ID> {
    default Specification<T> isActive() {
        return (root, query, cb) -> cb.equal(root.get("statusCode"), "active");
    }

    default Specification<T> withoutTerminated() {
        return (root, query, cb) -> cb.notEqual(root.get("statusCode"), "terminated");
    }

    default List<T> terminated() {
        return findAll((root, query, cb) -> cb.equal(root.get("statusCode"), "terminated"));
    }

    default Page<T> terminatedPageable(Pageable pageable) {
        return findAll((root, query, cb) -> cb.equal(root.get("statusCode"), "terminated"), pageable);
    }
}
