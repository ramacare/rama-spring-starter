package org.rama.demo.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@AutoConfigureHttpGraphQlTester
class AuthDirectiveIT {

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void archiveBook_withoutAuth_shouldFail() {
        Book b = transactionTemplate.execute(s -> bookRepository.saveAndFlush(new Book("anon archive")));
        graphQlTester.document("""
                mutation($id: ID!) { archiveBook(input: {id: $id}) { id } }
                """)
                .variable("id", b.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void archiveBook_withUserRole_shouldFail() {
        Book b = transactionTemplate.execute(s -> bookRepository.saveAndFlush(new Book("user archive")));
        graphQlTester.mutate().headers(h -> h.set("X-API-KEY", "demo-user-key")).build()
                .document("""
                    mutation($id: ID!) { archiveBook(input: {id: $id}) { id } }
                    """)
                .variable("id", b.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void archiveBook_withAdminRole_shouldSucceedAndSoftDelete() {
        Book b = transactionTemplate.execute(s -> bookRepository.saveAndFlush(new Book("admin archive")));
        graphQlTester.mutate().headers(h -> h.set("X-API-KEY", "demo-admin-key")).build()
                .document("""
                    mutation($id: ID!) { archiveBook(input: {id: $id}) { id statusCode } }
                    """)
                .variable("id", b.getId())
                .execute()
                .path("archiveBook.statusCode").entity(String.class).isEqualTo("terminated");
    }
}
