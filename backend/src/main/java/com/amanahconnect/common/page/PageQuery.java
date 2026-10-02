package com.amanahconnect.common.page;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The standard list parameters: {@code ?page=0&size=20&sort=name,desc}.
 *
 * <p>{@code page} is zero-based, {@code size} defaults to 20 and is at most 100 (larger values are
 * rejected with a 400, not silently clamped). {@code sort} may repeat or use the comma form
 * ({@code sort=name,desc}); only fields on the endpoint's {@link SortWhitelist} are accepted. Bind it
 * with {@code @Valid PageQuery query} and turn it into a {@code PageRequest} with
 * {@link SortWhitelist#toPageRequest}.
 */
public record PageQuery(
        @Min(value = 0, message = "page must be 0 or greater") Integer page,
        @Min(value = 1, message = "size must be at least 1") @Max(value = 100, message = "size must be at most 100") Integer size,
        @Size(max = 6, message = "too many sort parameters") List<String> sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }

    public List<String> sortOrEmpty() {
        return sort == null ? List.of() : sort;
    }
}
