package org.rama.demo.repository.book;

import org.rama.demo.entity.book.BookReview;
import org.rama.repository.BaseRepository;
import org.rama.repository.SoftDeleteRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface BookReviewRepository extends
        BaseRepository<BookReview, String>,
        SoftDeleteRepository<BookReview, String>,
        QuerydslPredicateExecutor<BookReview> {
}
