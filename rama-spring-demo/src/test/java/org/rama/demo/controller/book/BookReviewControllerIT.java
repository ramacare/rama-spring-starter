package org.rama.demo.controller.book;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.rama.demo.repository.book.BookReviewRepository;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@AutoConfigureHttpGraphQlTester
class BookReviewControllerIT {

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired BookRepository bookRepository;
    @Autowired BookReviewRepository bookReviewRepository;
    @Autowired SystemBufferRepository systemBufferRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void createAndUpdateBookReview_shouldWriteRevisionRows() throws Exception {
        Book book = transactionTemplate.execute(s ->
                bookRepository.saveAndFlush(new Book("Reviewed Book")));

        String reviewId = graphQlTester.document("""
                        mutation($bookId: ID!) {
                          createBookReview(input: {bookId: $bookId, reviewer: "alice", rating: 5, comment: "Great"}) { id }
                        }
                        """)
                .variable("bookId", book.getId())
                .execute()
                .path("createBookReview.id").entity(String.class).get();

        graphQlTester.document("""
                        mutation($id: ID!) {
                          updateBookReview(input: {id: $id, rating: 4}) { id rating }
                        }
                        """)
                .variable("id", reviewId)
                .execute()
                .path("updateBookReview.rating").entity(Integer.class).isEqualTo(4);

        // Revision rows are written AFTER commit via @Async into system_buffer. Give a brief pause.
        Thread.sleep(2000);

        // Revisions now go through system_buffer outbox (not JPA revision table).
        List<SystemBuffer> buffered = systemBufferRepository.findAll().stream()
                .filter(r -> "revision".equals(r.getBufferType()))
                .filter(r -> r.getPayload().contains(reviewId))
                .toList();

        assertThat(buffered).hasSizeGreaterThanOrEqualTo(1);
    }
}
