package org.rama.demo.controller.book;

import lombok.RequiredArgsConstructor;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class BookController {

    private final BookRepository bookRepository;
    private final GenericEntityService genericEntityService;

    @QueryMapping
    public Optional<Book> book(@Argument String id) {
        return bookRepository.findById(id);
    }

    @QueryMapping
    public List<Book> books(@Argument Map<String, Object> filter) {
        return bookRepository.findAll();
    }

    @MutationMapping
    public Optional<Book> createBook(@Argument Map<String, Object> input) {
        String id = Objects.toString(input.get("id"), null);
        return genericEntityService.createEntity(Book.class, bookRepository, id, input);
    }

    @MutationMapping
    public Optional<Book> updateBook(@Argument Map<String, Object> input) {
        String id = Objects.toString(input.get("id"), null);
        if (id == null) throw new IllegalArgumentException("updateBook requires input.id");
        return genericEntityService.updateEntity(Book.class, bookRepository, id, input);
    }

    @MutationMapping
    public Optional<Book> deleteBook(@Argument Map<String, Object> input) {
        String id = Objects.toString(input.get("id"), null);
        if (id == null) throw new IllegalArgumentException("deleteBook requires input.id");
        return genericEntityService.hardDeleteEntity(Book.class, bookRepository, id);
    }
}
