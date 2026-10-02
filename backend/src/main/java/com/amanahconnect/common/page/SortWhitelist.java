package com.amanahconnect.common.page;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The sort fields an endpoint allows. API names map to entity properties, so clients never name a
 * database column or a property that was not meant to be sortable (including ones that would leak
 * data through ordering).
 */
public final class SortWhitelist {

    private final Map<String, String> allowed;
    private final Sort defaultSort;

    private SortWhitelist(Map<String, String> allowed, Sort defaultSort) {
        this.allowed = allowed;
        this.defaultSort = defaultSort;
    }

    /**
     * @param defaultSort used when the client sends no sort, e.g. {@code Sort.by(DESC, "createdAt")}
     * @param apiToProperty allowed API field name to entity property, e.g. {@code Map.of("name", "fullName")}
     */
    public static SortWhitelist of(Sort defaultSort, Map<String, String> apiToProperty) {
        return new SortWhitelist(new LinkedHashMap<>(apiToProperty), defaultSort);
    }

    /** Shorthand when the API names equal the entity properties. */
    public static SortWhitelist of(Sort defaultSort, String... fields) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String field : fields) {
            map.put(field, field);
        }
        return of(defaultSort, map);
    }

    public PageRequest toPageRequest(PageQuery query) {
        List<String> problems = new ArrayList<>();
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        if (page < 0) {
            problems.add("page: must be 0 or greater");
        }
        if (size < 1 || size > PageQuery.MAX_SIZE) {
            problems.add("size: must be between 1 and " + PageQuery.MAX_SIZE);
        }
        Sort sort = parseSort(query.sortOrEmpty(), problems);
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The list parameters are invalid.", problems);
        }
        // A unique tiebreaker keeps page boundaries stable when the sorted column has duplicates.
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(page, size, sort);
    }

    private Sort parseSort(List<String> tokens, List<String> problems) {
        if (tokens.isEmpty()) {
            return defaultSort;
        }
        List<Sort.Order> orders = new ArrayList<>();
        Sort.Order pendingOrder = null;
        for (String raw : tokens) {
            String token = raw.trim();
            if (token.equalsIgnoreCase("asc") || token.equalsIgnoreCase("desc")) {
                if (pendingOrder == null) {
                    problems.add("sort: direction '" + token + "' must follow a field");
                } else {
                    orders.set(orders.size() - 1, pendingOrder.with(Sort.Direction.fromString(token)));
                    pendingOrder = null;
                }
                continue;
            }
            String property = allowed.get(token);
            if (property == null) {
                problems.add("sort: '" + abbreviate(token) + "' is not sortable; allowed: " + String.join(", ", allowed.keySet()));
                pendingOrder = null;
                continue;
            }
            if (orders.stream().anyMatch(o -> o.getProperty().equals(property))) {
                problems.add("sort: '" + token + "' is given more than once");
                continue;
            }
            pendingOrder = Sort.Order.asc(property);
            orders.add(pendingOrder);
        }
        return problems.isEmpty() ? Sort.by(orders) : defaultSort;
    }

    private static String abbreviate(String value) {
        String clean = value.replaceAll("[^A-Za-z0-9_.-]", "?");
        return clean.length() > 40 ? clean.substring(0, 40).toLowerCase(Locale.ROOT) + "..." : clean;
    }
}
