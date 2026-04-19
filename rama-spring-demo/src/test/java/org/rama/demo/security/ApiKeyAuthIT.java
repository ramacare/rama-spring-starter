package org.rama.demo.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@AutoConfigureHttpGraphQlTester
class ApiKeyAuthIT {

    @Autowired HttpGraphQlTester graphQlTester;

    @Test
    void createBook_withoutApiKey_shouldSucceed_becauseGraphqlPermitAll() {
        graphQlTester.document("""
                mutation { createBook(input: {title: "anon"}) { id } }
                """)
                .execute()
                .path("createBook.id").hasValue();
    }

    @Test
    void createBook_withValidApiKey_shouldSucceed() {
        graphQlTester.mutate().headers(h -> h.set("X-API-KEY", "demo-admin-key")).build()
                .document("""
                    mutation { createBook(input: {title: "authed"}) { id } }
                    """)
                .execute()
                .path("createBook.id").hasValue();
    }
}
