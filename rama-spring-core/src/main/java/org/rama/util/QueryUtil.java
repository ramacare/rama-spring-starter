package org.rama.util;

import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.*;
import jakarta.persistence.Embedded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryUtil {
    private static final Logger log = LoggerFactory.getLogger(QueryUtil.class);

    private QueryUtil() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T, S extends EntityPathBase<T>> BooleanExpression withoutTerminated(S pathBase) {
        BooleanExpression predicate = Expressions.TRUE;
        try {
            Path<?> fieldPath = resolveFirstStatusPath(pathBase);
            if (fieldPath instanceof StringPath stringPath) {
                predicate = stringPath.ne("terminated");
            } else if (fieldPath instanceof EnumPath<?> rawEnumPath) {
                Class<?> enumType = rawEnumPath.getType();
                if (enumType != null && enumType.isEnum()) {
                    Enum enumValue = Enum.valueOf((Class<Enum>) enumType, "terminated");
                    predicate = ((EnumPath) rawEnumPath).ne(enumValue);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to build withoutTerminated predicate", ex);
        }
        return predicate;
    }

    public static BooleanExpression date(DateTimePath<OffsetDateTime> dateTimePath, LocalDate date) {
        return dateBetween(dateTimePath, date, date);
    }

    public static BooleanExpression dateBetween(DateTimePath<OffsetDateTime> dateTimePath, LocalDate startDate, LocalDate endDate) {
        OffsetDateTime startOfDay = startDate.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        OffsetDateTime endOfDay = endDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        return dateTimePath.between(startOfDay, endOfDay);
    }

    public static BooleanExpression dateTimeWithin(DateTimePath<OffsetDateTime> dateTimePath, Duration duration) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault());
        return dateTimePath.between(now.minus(duration), now);
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression example(T example, S pathBase) {
        return exampleReflection(example, new PathBuilder<>(Object.class, pathBase.getMetadata()), "");
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression example(Map<String, Object> example, S pathBase) {
        return exampleMap(example, pathBase);
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression WithoutTerminated(S pathBase) {
        return withoutTerminated(pathBase);
    }

    public static BooleanExpression Date(DateTimePath<OffsetDateTime> dateTimePath, LocalDate date) {
        return date(dateTimePath, date);
    }

    public static BooleanExpression DateBetween(DateTimePath<OffsetDateTime> dateTimePath, LocalDate startDate, LocalDate endDate) {
        return dateBetween(dateTimePath, startDate, endDate);
    }

    public static BooleanExpression DateTimeWithin(DateTimePath<OffsetDateTime> dateTimePath, Duration duration) {
        return dateTimeWithin(dateTimePath, duration);
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression Example(T example, S pathBase) {
        return example(example, pathBase);
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression Example(Map<String, Object> example, S pathBase) {
        return example(example, pathBase);
    }

    public static <T, S extends EntityPathBase<T>> BooleanExpression ExampleMap(Map<String, Object> fieldValues, S pathBase) {
        return exampleMap(fieldValues, pathBase);
    }

    public static <T> BooleanExpression ExampleReflection(T example, PathBuilder<Object> pathBuilder, String parentPath) {
        return exampleReflection(example, pathBuilder, parentPath);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T, S extends EntityPathBase<T>> BooleanExpression exampleMap(Map<String, Object> fieldValues, S pathBase) {
        List<BooleanExpression> predicates = new ArrayList<>();
        Map<String, Object> flatFields = new LinkedHashMap<>();
        flattenMap("", fieldValues, flatFields);

        for (Map.Entry<String, Object> entry : flatFields.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            try {
                Path<?> fieldPath = resolvePath(pathBase, fieldName.split("\\."), 0);

                if (fieldPath instanceof StringPath stringPath) {
                    String stringValue = value.toString();
                    predicates.add(stringValue.startsWith("#contains")
                            ? stringPath.contains(stringValue.substring("#contains".length()).trim())
                            : stringPath.eq(stringValue));
                } else if (fieldPath instanceof NumberPath<?> numberPath) {
                    addNumberPredicate(predicates, numberPath, value);
                } else if (fieldPath instanceof com.querydsl.core.types.dsl.BooleanPath booleanPath) {
                    predicates.add(booleanPath.eq(value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString())));
                } else if (fieldPath instanceof DateTimePath<?> dateTimePath) {
                    addDateTimePredicate(predicates, dateTimePath, value);
                } else if (fieldPath instanceof DatePath<?> datePath) {
                    addDatePredicate(predicates, datePath, value);
                } else if (fieldPath instanceof EnumPath<?> rawEnumPath) {
                    Class<?> enumType = rawEnumPath.getType();
                    if (enumType != null && enumType.isEnum()) {
                        Enum enumValue = Enum.valueOf((Class<Enum>) enumType, value.toString());
                        predicates.add(((EnumPath) rawEnumPath).eq(enumValue));
                    }
                }
            } catch (Exception ex) {
                log.error("Failed to build predicate for field '{}'", fieldName, ex);
            }
        }

        return predicates.stream().reduce(BooleanExpression::and).orElse(Expressions.TRUE);
    }

    public static Path<?> resolvePath(Object pathBase, String[] splitFields, int index) throws Exception {
        if (index >= splitFields.length) {
            return (Path<?>) pathBase;
        }

        Field field = findField(pathBase.getClass(), splitFields[index]);
        if (field == null) {
            throw new NoSuchFieldException("Field '%s' not found in %s".formatted(splitFields[index], pathBase.getClass().getName()));
        }

        field.setAccessible(true);
        Object fieldPath = field.get(pathBase);
        if (fieldPath instanceof CollectionPathBase<?, ?, ?> collectionPath) {
            return resolvePath(collectionPath.any(), splitFields, index + 1);
        }
        if (fieldPath instanceof EntityPathBase<?> entityPath) {
            return resolvePath(entityPath, splitFields, index + 1);
        }
        if (fieldPath instanceof Path<?>) {
            return resolvePath(fieldPath, splitFields, index + 1);
        }
        throw new IllegalStateException("Field '%s' in %s is not a QueryDSL Path".formatted(splitFields[index], pathBase.getClass().getName()));
    }

    public static <T> BooleanExpression exampleReflection(T example, PathBuilder<Object> pathBuilder, String parentPath) {
        List<BooleanExpression> predicates = new ArrayList<>();
        for (Field field : example.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(example);
                if (value == null) {
                    continue;
                }

                String fieldPath = parentPath.isEmpty() ? field.getName() : parentPath + "." + field.getName();
                if (field.isAnnotationPresent(Embedded.class)) {
                    predicates.add(exampleReflection(value, pathBuilder, fieldPath));
                } else if (value instanceof String stringValue) {
                    predicates.add(pathBuilder.getString(fieldPath).eq(stringValue));
                } else {
                    predicates.add(pathBuilder.get(fieldPath).eq(value));
                }
            } catch (IllegalAccessException ex) {
                log.error("Failed to build reflection predicate for field '{}'", field.getName(), ex);
            }
        }
        return predicates.stream().reduce(BooleanExpression::and).orElse(Expressions.TRUE);
    }

    @SuppressWarnings("rawtypes")
    private static Path<?> resolveFirstStatusPath(Object pathBase) throws Exception {
        // First try the conventional field name
        try {
            return resolvePath(pathBase, new String[]{"statusCode"}, 0);
        } catch (Exception ignored) {
        }

        // Scan for any EnumPath field whose enum type has a "terminated" constant
        for (Field field : pathBase.getClass().getDeclaredFields()) {
            if (!EnumPath.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            EnumPath<?> enumPath = (EnumPath<?>) field.get(pathBase);
            if (enumPath != null) {
                Class<?> enumType = enumPath.getType();
                if (enumType != null && enumType.isEnum()) {
                    for (Object constant : enumType.getEnumConstants()) {
                        if ("terminated".equals(((Enum) constant).name())) {
                            return enumPath;
                        }
                    }
                }
            }
        }

        throw new NoSuchFieldException(
                "No status field with 'terminated' enum constant found in " + pathBase.getClass().getSimpleName());
    }

    private static void flattenMap(String prefix, Map<String, Object> fieldValues, Map<String, Object> flatFields) {
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedRawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) nestedRawMap;
                flattenMap(key, nested, flatFields);
            } else {
                flatFields.put(key, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addNumberPredicate(List<BooleanExpression> predicates, NumberPath<?> numberPath, Object value) {
        try {
            if (numberPath.getType().equals(BigDecimal.class)) {
                predicates.add(((NumberPath<BigDecimal>) numberPath).eq(new BigDecimal(value.toString())));
            } else if (numberPath.getType().equals(Integer.class)) {
                predicates.add(((NumberPath<Integer>) numberPath).eq(Integer.parseInt(value.toString())));
            } else if (numberPath.getType().equals(Long.class)) {
                predicates.add(((NumberPath<Long>) numberPath).eq(Long.parseLong(value.toString())));
            } else if (numberPath.getType().equals(Double.class)) {
                predicates.add(((NumberPath<Double>) numberPath).eq(Double.parseDouble(value.toString())));
            } else if (numberPath.getType().equals(Float.class)) {
                predicates.add(((NumberPath<Float>) numberPath).eq(Float.parseFloat(value.toString())));
            } else if (numberPath.getType().equals(Short.class)) {
                predicates.add(((NumberPath<Short>) numberPath).eq(Short.parseShort(value.toString())));
            }
        } catch (Exception ex) {
            log.error("Failed to parse number value '{}'", value, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addDateTimePredicate(List<BooleanExpression> predicates, DateTimePath<?> dateTimePath, Object value) {
        try {
            if (dateTimePath.getType().equals(OffsetDateTime.class)) {
                predicates.add(((DateTimePath<OffsetDateTime>) dateTimePath).eq(value instanceof OffsetDateTime odt ? odt : OffsetDateTime.parse(value.toString())));
            } else if (dateTimePath.getType().equals(LocalDateTime.class)) {
                predicates.add(((DateTimePath<LocalDateTime>) dateTimePath).eq(value instanceof LocalDateTime ldt ? ldt : LocalDateTime.parse(value.toString())));
            } else if (dateTimePath.getType().equals(Instant.class)) {
                predicates.add(((DateTimePath<Instant>) dateTimePath).eq(value instanceof Instant instant ? instant : Instant.parse(value.toString())));
            }
        } catch (Exception ex) {
            log.error("Failed to parse datetime value '{}'", value, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addDatePredicate(List<BooleanExpression> predicates, DatePath<?> datePath, Object value) {
        try {
            if (datePath.getType().equals(LocalDate.class)) {
                predicates.add(((DatePath<LocalDate>) datePath).eq(value instanceof LocalDate ld ? ld : LocalDate.parse(value.toString())));
            }
        } catch (Exception ex) {
            log.error("Failed to parse date value '{}'", value, ex);
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            return type != null ? type.getField(fieldName) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
