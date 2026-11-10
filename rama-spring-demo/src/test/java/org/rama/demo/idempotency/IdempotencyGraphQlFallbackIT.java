package org.rama.demo.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rama.demo.repository.book.BookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter#67, end to end: the demo's {@code createBook} is an {@code @IdempotentMutation}
 * taking {@code @Argument Map<String, Object> input}, called over HTTP with no
 * {@code Idempotency-Key} header — the shape of a frontend that cannot send one.
 *
 * <p>Before the fix the second call, two seconds later, fell in a new second bucket and
 * created a second book.
 */
@Tag("integration")
@Tag("issue-67")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import(IdempotencyGraphQlFallbackIT.ClockOverride.class)
class IdempotencyGraphQlFallbackIT {

    static final Instant T0 = Instant.parse("2026-05-15T00:00:00.900Z");

    private static final String CREATE_BOOK = """
            mutation($title: String!) {
              createBook(input: {title: $title, author: "Dup Tester"}) { id title }
            }
            """;

    @Autowired HttpGraphQlTester graphQlTester;
    @Autowired BookRepository bookRepository;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        clock.set(T0);
    }

    @Test
    @Tag("TC-67-21")
    void sameCreateBookTwoSecondsApart_withoutHeader_createsOneBook() {
        String title = "Idempotent " + UUID.randomUUID();

        String firstId = createBook(title);
        clock.advance(Duration.ofSeconds(2));
        String secondId = createBook(title);

        assertThat(secondId).as("the duplicate replays the first response").isEqualTo(firstId);
        assertThat(booksTitled(title)).isEqualTo(1L);
    }

    @Test
    @Tag("TC-67-22")
    void sameCreateBookAfterTheWindow_createsASecondBook() {
        String title = "Repeated " + UUID.randomUUID();

        String firstId = createBook(title);
        clock.advance(Duration.ofSeconds(6));
        String secondId = createBook(title);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(booksTitled(title)).isEqualTo(2L);
    }

    private String createBook(String title) {
        return graphQlTester.document(CREATE_BOOK)
                .variable("title", title)
                .execute()
                .path("createBook.id").entity(String.class).get();
    }

    private long booksTitled(String title) {
        return bookRepository.findAll().stream().filter(b -> title.equals(b.getTitle())).count();
    }

    @TestConfiguration
    static class ClockOverride {

        @Bean(name = "idempotencyClock")
        @Primary
        MutableClock idempotencyClock() {
            return new MutableClock(T0);
        }
    }
}
