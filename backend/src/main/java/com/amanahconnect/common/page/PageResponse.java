package com.amanahconnect.common.page;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** The standard list envelope: {@code {"items": [...], "total": 42, "page": 0, "size": 20}}. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    /** Maps entities to DTOs on the way out, so an entity never reaches the API. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().<T>map(mapper).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize());
    }
}
