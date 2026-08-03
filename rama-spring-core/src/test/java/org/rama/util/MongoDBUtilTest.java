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

    @Test
    void notEqual_requiresFieldPresence_soMissingFieldIsExcluded() {
        // #37: `!=` must mean "present and not equal", not Mongo's raw $ne (which also
        // matches documents missing the field). A row without meta.requestType must NOT match.
        Document criteria = fieldCriteria(
                List.of(Map.of("key", "meta.requestType", "value", "download", "operator", "!=")),
                "meta.requestType");

        assertThat(criteria.get("$ne")).isEqualTo("download");
        assertThat(criteria.get("$exists")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void withoutTerminated_stillMatchesDocumentsMissingStatusCode() {
        // A document with no statusCode is not soft-deletable, so it must still be returned.
        // withoutTerminated must therefore NOT require the field to exist.
        Document criteria = fieldCriteria(
                MongoDBUtil.withoutTerminated(new ArrayList<>()),
                "statusCode");

        // excludes terminated...
        assertThat(criteria.get("$nin")).isEqualTo(List.of("terminated"));
        // ...but does not require statusCode to be present
        assertThat(criteria.containsKey("$exists")).isFalse();
    }

    @Test
    void withoutTerminated_customFieldAndValue() {
        Document criteria = fieldCriteria(
                MongoDBUtil.withoutTerminated(new ArrayList<>(), "state", "deleted"),
                "state");

        assertThat(criteria.get("$nin")).isEqualTo(List.of("deleted"));
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
