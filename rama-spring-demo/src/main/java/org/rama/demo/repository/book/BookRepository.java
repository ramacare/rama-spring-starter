package org.rama.demo.repository.book;

import org.rama.demo.entity.book.Book;
import org.rama.repository.BaseRepository;
import org.rama.repository.SoftDeleteRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface BookRepository extends
        BaseRepository<Book, String>,
        SoftDeleteRepository<Book, String>,
        QuerydslPredicateExecutor<Book> {
}
