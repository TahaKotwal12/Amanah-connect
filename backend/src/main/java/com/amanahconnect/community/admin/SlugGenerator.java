package com.amanahconnect.community.admin;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Turns a community name into a URL-safe slug ({@code ^[a-z0-9]+(-[a-z0-9]+)*$}, at most 80 characters). */
public final class SlugGenerator {

    public static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int BASE_MAX = 70; // leaves room for a numeric suffix within 80

    private SlugGenerator() {}

    public static String base(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > BASE_MAX) {
            slug = slug.substring(0, BASE_MAX).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "community" : slug;
    }

    /** The first free slug: the base, then base-2, base-3, ... */
    public static String unique(String name, Predicate<String> taken) {
        String base = base(name);
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = base + "-" + n;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free slug for " + base);
    }
}
