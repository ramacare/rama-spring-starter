package org.rama.demo.controller.book;

import lombok.RequiredArgsConstructor;
import org.rama.demo.entity.book.Book;
import org.rama.demo.entity.book.BookReview;
import org.rama.demo.repository.book.BookRepository;
import org.rama.demo.repository.book.BookReviewRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class BookReviewController {

    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final GenericEntityService genericEntityService;

    @MutationMapping
    public Optional<BookReview> createBookReview(@Argument Map<String, Object> input) {
        String id = Objects.toString(input.get("id"), null);
        Map<String, Object> resolved = resolveBookReference(input);
        return genericEntityService.createEntity(BookReview.class, bookReviewRepository, id, resolved);
    }

    @MutationMapping
    public Optional<BookReview> updateBookReview(@Argument Map<String, Object> input) {
        String id = Objects.toString(input.get("id"), null);
        if (id == null) throw new IllegalArgumentException("updateBookReview requires input.id");
        Map<String, Object> resolved = resolveBookReference(input);
        return genericEntityService.updateEntity(BookReview.class, bookReviewRepository, id, resolved);
    }

    @SchemaMapping(typeName = "BookReview", field = "bookId")
    public String bookId(BookReview review) {
        return review.getBook() != null ? review.getBook().getId() : null;
    }

    private Map<String, Object> resolveBookReference(Map<String, Object> input) {
        if (!input.containsKey("bookId")) return input;
        String bookId = Objects.toString(input.get("bookId"), null);
        Map<String, Object> copy = new HashMap<>(input);
        copy.remove("bookId");
        if (bookId != null) {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));
            copy.put("book", book);
        }
        return copy;
    }
}
