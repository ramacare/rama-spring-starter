package org.rama.util;

import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class MongoDBUtilTest {

    /** Extracts the per-field criteria Document (e.g. {@code {$ne: .., $exists: ..}}) for a single-criterion input. */
    private static Document fieldCriteria(List<Map<String, Object>> input, String field) {
        Document root = MongoDBUtil.criteriaBuilder(input).getCriteriaObject();
        List<Document> and = root.getList("$and", Document.class);
        return (Document) and.get(0).get(field);
    }

    private static String json(List<Map<String, Object>> input) {
        return MongoDBUtil.criteriaBuilder(input).getCriteriaObject().toJson();
    }

    @Test
    void notEqual_staysRawNe_andMatchesDocumentsMissingField() {
        // "!=" is unchanged (backwards compatible): raw $ne also matches docs missing the field.
        Document criteria = fieldCriteria(
                List.of(Map.of("key", "meta.requestType", "value", "download", "operator", "!=")),
                "meta.requestType");

        assertThat(criteria.get("$ne")).isEqualTo("download");
        assertThat(criteria.containsKey("$exists")).isFalse();
    }

    @Test
    void neexists_requiresFieldPresence_scalar() {
        // #37: opt-in "present and not equal" — a document missing the field is excluded.
        Document criteria = fieldCriteria(
                List.of(Map.of("key", "meta.requestType", "value", "download", "operator", "neexists")),
                "meta.requestType");

        assertThat(criteria.get("$ne")).isEqualTo("download");
        assertThat(criteria.get("$exists")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void neexists_isCaseInsensitive() {
        // criteriaBuilder lowercases the operator, so "neExists" resolves to the same handler.
        Document criteria = fieldCriteria(
                List.of(Map.of("key", "code", "value", "x", "operator", "neExists")),
                "code");

        assertThat(criteria.get("$exists")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void neexists_requiresFieldPresence_date() {
        // Date-valued neexists must require presence too: field exists AND not on that day.
        String bson = json(List.of(Map.of("key", "visitDate", "value", "2026-08-04", "operator", "neexists")));

        assertThat(bson).contains("$exists").contains("$nor");
    }

    @Test
    void notEqual_date_staysRawNor_matchesMissing() {
        // "!=" on a date remains raw ($nor of the day range) — no $exists, so missing still matches.
        String bson = json(List.of(Map.of("key", "visitDate", "value", "2026-08-04", "operator", "!=")));

        assertThat(bson).contains("$nor");
        assertThat(bson).doesNotContain("$exists");
    }

    @Test
    void withoutTerminated_matchesDocumentsMissingStatusCode() {
        // Unchanged: withoutTerminated uses raw "!=", so a document with no statusCode is returned.
        Document criteria = fieldCriteria(
                MongoDBUtil.withoutTerminated(new ArrayList<>()),
                "statusCode");

        assertThat(criteria.get("$ne")).isEqualTo("terminated");
        assertThat(criteria.containsKey("$exists")).isFalse();
    }

    @Test
    void equals_isUnchanged_scalarEquality() {
        Criteria c = MongoDBUtil.criteriaBuilder(
                List.of(Map.of("key", "name", "value", "bob", "operator", "=")));
        Document first = c.getCriteriaObject().getList("$and", Document.class).get(0);
        assertThat(first.getString("name")).isEqualTo("bob");
    }
}
