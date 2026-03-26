package org.rama.starter.mongo.indexing;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IndexFieldExtractor {
    private IndexFieldExtractor() {
    }

    public static LinkedHashMap<String, Sort.Direction> extractOptimizedIndexFields(Query query) {
        LinkedHashMap<String, Sort.Direction> result = new LinkedHashMap<>();

        Document sortDoc = query.getSortObject();
        List<String> sortedFieldOrder = new ArrayList<>();
        Map<String, Sort.Direction> sortDirections = new HashMap<>();
        for (Map.Entry<String, Object> entry : sortDoc.entrySet()) {
            Sort.Direction direction = getDirection(entry.getValue());
            if (entry.getKey() != null && direction != null) {
                sortedFieldOrder.add(entry.getKey());
                sortDirections.put(entry.getKey(), direction);
            }
        }
        for (String field : sortedFieldOrder) {
            result.put(field, sortDirections.get(field));
        }

        Map<String, FieldInfo> filterFields = new HashMap<>();
        extractFieldsWithDepth(query.getQueryObject(), "", filterFields);
        filterFields.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getValue().depth))
                .forEach(entry -> result.putIfAbsent(entry.getKey(), Sort.Direction.ASC));
        return result;
    }

    private static Sort.Direction getDirection(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == -1 ? Sort.Direction.DESC : Sort.Direction.ASC;
        }
        return null;
    }

    private static void extractFieldsWithDepth(Document doc, String prefix, Map<String, FieldInfo> fields) {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.startsWith("$")) {
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Document subDoc) {
                            extractFieldsWithDepth(subDoc, prefix, fields);
                        }
                    }
                }
                continue;
            }
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            fields.putIfAbsent(fullKey, new FieldInfo(fullKey, fullKey.split("\\.").length - 1));
            if (value instanceof Document subDoc) {
                extractFieldsWithDepth(subDoc, fullKey, fields);
            }
        }
    }

    private record FieldInfo(String fieldName, int depth) {
    }
}
