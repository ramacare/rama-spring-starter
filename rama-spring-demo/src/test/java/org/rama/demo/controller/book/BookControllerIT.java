package org.rama.demo.controller.book;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.entity.book.Book;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@AutoConfigureHttpGraphQlTester
@Import(BookControllerIT.PermitAllSecurityConfig.class)
class BookControllerIT {

    @TestConfiguration
    static class PermitAllSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
            return http.build();
        }
    }

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired BookRepository bookRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void createBook_whenInputValid_shouldPersistAndReturnBook() {
        graphQlTester.document("""
            mutation {
              createBook(input: {title: "Clean Code", author: "Uncle Bob", isbn: "9780132350884", publishedYear: 2008}) {
                id title author isbn publishedYear statusCode
              }
            }
            """)
            .execute()
            .path("createBook.title").entity(String.class).isEqualTo("Clean Code")
            .path("createBook.id").entity(String.class).satisfies(id -> assertThat(id).isNotBlank())
            .path("createBook.statusCode").entity(String.class).isEqualTo("active");
    }

    @Test
    void updateBook_whenExisting_shouldPersistChanges() {
        Book seeded = transactionTemplate.execute(s -> {
            Book b = new Book("Seed Title");
            b.setAuthor("Original");
            return bookRepository.saveAndFlush(b);
        });

        graphQlTester.document("""
            mutation($id: ID!) {
              updateBook(input: {id: $id, title: "Seed Title", author: "Updated"}) { id author }
            }
            """)
            .variable("id", seeded.getId())
            .execute()
            .path("updateBook.author").entity(String.class).isEqualTo("Updated");
    }

    @Test
    void deleteBook_whenExisting_shouldRemoveRow() {
        Book seeded = transactionTemplate.execute(s ->
            bookRepository.saveAndFlush(new Book("To Delete")));

        graphQlTester.document("""
            mutation($id: ID!) {
              deleteBook(input: {id: $id}) { id }
            }
            """)
            .variable("id", seeded.getId())
            .execute()
            .path("deleteBook.id").entity(String.class).isEqualTo(seeded.getId());

        assertThat(bookRepository.findById(seeded.getId())).isEmpty();
    }
}
