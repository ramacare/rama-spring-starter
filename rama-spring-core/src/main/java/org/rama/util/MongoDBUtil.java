package org.rama.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
public final class MongoDBUtil {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private MongoDBUtil() {
    }

    public static Criteria criteriaBuilder(List<Map<String, Object>> criteriaListInput) {
        List<Criteria> criteriaList = new ArrayList<>();

        for (Map<String, Object> criteriaMap : criteriaListInput) {
            String key = criteriaMap.get("key").toString();
            Object rawValue = criteriaMap.get("value");
            String operator = criteriaMap.getOrDefault("operator", "=").toString().toLowerCase();

            try {
                Criteria newCriteria;

                if (List.of("in", "nin").contains(operator)) {
                    List<Object> parsedList = parseList(rawValue.toString());
                    newCriteria = operator.equals("in") ? Criteria.where(key).in(parsedList) : Criteria.where(key).nin(parsedList);
                } else if (operator.equals("between")) {
                    List<String> parts = splitWithEscape(rawValue.toString());
                    if (parts.size() != 2) {
                        throw new IllegalArgumentException("between operator requires two values");
                    }
                    Object parsedStart = tryParseDateTime(parts.get(0));
                    Object parsedEnd = tryParseDateTime(parts.get(1));

                    Object gteValue;
                    Object lteValue;

                    if (parsedStart instanceof LocalDate startDate) {
                        gteValue = convertToDateInstant(startDate, true);
                    } else if (parsedStart instanceof OffsetDateTime startDatetime) {
                        gteValue = convertToDateInstant(startDatetime);
                    } else {
                        gteValue = parsedStart;
                    }

                    if (parsedEnd instanceof LocalDate endDate) {
                        lteValue = convertToDateInstant(endDate, false);
                    } else if (parsedEnd instanceof OffsetDateTime endDatetime) {
                        lteValue = convertToDateInstant(endDatetime);
                    } else {
                        lteValue = parsedEnd;
                    }

                    if (NumberUtils.isCreatable(lteValue.toString()) && NumberUtils.isCreatable(gteValue.toString())) {
                        Object gteValueNumber = NumberUtils.createDouble(gteValue.toString());
                        Object lteValueNumber = NumberUtils.createDouble(lteValue.toString());
                        newCriteria = new Criteria().orOperator(Criteria.where(key).gte(gteValue).lte(lteValue), Criteria.where(key).gte(gteValueNumber).lte(lteValueNumber));
                    } else {
                        newCriteria = Criteria.where(key).gte(gteValue).lte(lteValue);
                    }
                } else if (operator.equals("exists")) {
                    boolean exists = Boolean.parseBoolean(rawValue.toString());
                    newCriteria = Criteria.where(key).exists(exists);
                } else if (operator.equals("like")) {
                    newCriteria = Criteria.where(key).regex(".*" + Pattern.quote(rawValue.toString()) + ".*", "i");
                } else if (operator.equals("regex")) {
                    newCriteria = Criteria.where(key).regex(Pattern.quote(rawValue.toString()), "i");
                } else if (operator.equals("elmatch")) {
                    String pattern = ".*" + Pattern.quote(rawValue.toString()) + ".*";
                    Criteria elemMatch = Criteria.where("").regex(pattern, "i");
                    newCriteria = Criteria.where(key).elemMatch(elemMatch);
                } else if (operator.equals("elregex")) {
                    String pattern = Pattern.quote(rawValue.toString());
                    Criteria elemMatch = Criteria.where("").regex(pattern, "i");
                    newCriteria = Criteria.where(key).elemMatch(elemMatch);
                } else {
                    Object parsed = tryParseDateTime(rawValue.toString());
                    if (parsed instanceof LocalDate date) {
                        newCriteria = buildDateCriteria(key, date, operator);
                    } else {
                        if (parsed instanceof OffsetDateTime odt) {
                            parsed = convertToDateInstant(odt);
                        }
                        newCriteria = buildCriteriaParseNumber(key, parsed, operator);
                    }
                }

                criteriaList.add(newCriteria);
            } catch (Exception e) {
                log.error("Failed to process criteria key '{}': {}", criteriaMap.get("key"), e.getMessage());
                throw e;
            }
        }

        return criteriaList.isEmpty() ? new Criteria() : new Criteria().andOperator(criteriaList.toArray(new Criteria[0]));
    }

    public static List<Map<String, Object>> withoutTerminated(List<Map<String, Object>> criteriaListInput, String statusCodeField, Object terminatedValue) {
        List<Map<String, Object>> result = new ArrayList<>(criteriaListInput);
        result.add(Map.of("key", statusCodeField, "value", terminatedValue, "operator", "!="));
        return result;
    }

    public static List<Map<String, Object>> withoutTerminated(List<Map<String, Object>> criteriaListInput) {
        return withoutTerminated(criteriaListInput, "statusCode", "terminated");
    }

    private static Criteria buildDateCriteria(String key, LocalDate date, String operator) {
        Date startDate = convertToDateInstant(date, true);
        Date endDate = convertToDateInstant(date, false);

        return switch (operator) {
            case "=" -> Criteria.where(key).gte(startDate).lte(endDate);
            case "!=" -> new Criteria().norOperator(Criteria.where(key).gte(startDate).lte(endDate));
            case ">" -> Criteria.where(key).gt(endDate);
            case ">=" -> Criteria.where(key).gte(startDate);
            case "<" -> Criteria.where(key).lt(startDate);
            case "<=" -> Criteria.where(key).lte(endDate);
            default -> throw new UnsupportedOperationException("Unsupported operator for LocalDate: " + operator);
        };
    }

    private static Criteria buildCriteria(String key, Object value, String operator) {
        return switch (operator) {
            case "=" -> Criteria.where(key).is(value);
            case "!=" -> Criteria.where(key).ne(value);
            case ">" -> Criteria.where(key).gt(value);
            case ">=" -> Criteria.where(key).gte(value);
            case "<" -> Criteria.where(key).lt(value);
            case "<=" -> Criteria.where(key).lte(value);
            default -> throw new UnsupportedOperationException("Unsupported operator: " + operator);
        };
    }

    private static Criteria buildCriteriaParseNumber(String key, Object value, String operator) {
        if (NumberUtils.isCreatable(value.toString())) {
            Criteria stringCriteria = buildCriteria(key, value, operator);
            Criteria numberCriteria = buildCriteria(key, NumberUtils.createDouble(value.toString()), operator);
            return new Criteria().orOperator(stringCriteria, numberCriteria);
        }

        return buildCriteria(key, value, operator);
    }

    private static Object tryParseDateTime(String value) {
        try {
            return DateTimeUtil.parseFlexibleOffsetDateTime(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return DateTimeUtil.parseFlexibleLocalDate(value);
        } catch (DateTimeParseException ignored) {
        }
        return value;
    }

    private static Date convertToDateInstant(OffsetDateTime odt) {
        return Date.from(odt.toInstant());
    }

    @NotNull
    private static Date convertToDateInstant(LocalDate date, boolean startOfDay) {
        ZonedDateTime zoned = startOfDay ? date.atStartOfDay(ZONE) : date.plusDays(1).atStartOfDay(ZONE).minusNanos(1);
        return Date.from(zoned.toInstant());
    }

    private static List<String> splitWithEscape(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escape = false;

        for (char c : input.toCharArray()) {
            if (escape) {
                current.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == ',') {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    private static List<Object> parseList(String raw) {
        return splitWithEscape(raw).stream()
                .map(MongoDBUtil::tryParseDateTime)
                .map(val -> {
                    if (val instanceof OffsetDateTime odt) {
                        return Date.from(odt.toInstant());
                    }
                    if (val instanceof LocalDate ld) {
                        return Date.from(ld.atStartOfDay(ZONE).toInstant());
                    }
                    return val;
                })
                .toList();
    }
}
