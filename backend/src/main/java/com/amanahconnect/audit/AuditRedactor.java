package com.amanahconnect.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a value safe to store in an audit record: converts it to plain maps and lists, replaces every
 * secret with {@code [REDACTED]}, and caps string length.
 *
 * <p>Redaction works on two levels so that forgetting one does not leak:
 * <ul>
 *   <li><b>By key</b>: any key whose name (case, underscores and hyphens ignored) contains one of
 *       {@link #SENSITIVE_FRAGMENTS} or equals one of {@link #SENSITIVE_EXACT}. Over-redaction is
 *       preferred to leaking, so {@code tokenType} is redacted too.</li>
 *   <li><b>By value</b>: anything that looks like a JWT, a {@code Bearer} header, an {@code otpauth://}
 *       URI, a 256-bit URL-safe token or a SHA-256 hex digest, whatever key it sits under.</li>
 * </ul>
 * A test checks that every field name in a JPA entity that looks secret is covered here, so a new
 * secret-bearing column cannot be audited by accident.
 */
@Component
public class AuditRedactor {

    public static final String REDACTED = "[REDACTED]";

    /** A key is sensitive if its normalised name contains any of these. */
    public static final Set<String> SENSITIVE_FRAGMENTS =
            Set.of("password", "passwd", "secret", "token", "hash", "recovery", "authorization", "cookie", "apikey", "privatekey", "credential");

    /** Short names that are only sensitive as a whole word (a fragment would match innocent keys). */
    public static final Set<String> SENSITIVE_EXACT = Set.of("totp", "otp", "mfacode", "totpcode", "verificationcode", "pin", "cvv", "pwd", "otpauthuri", "salt", "signature");

    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$");
    private static final Pattern BEARER = Pattern.compile("^(?i)bearer\\s+\\S+$");
    private static final Pattern BASE64URL_256 = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_STRING = 2_000;

    private static final Logger log = LoggerFactory.getLogger(AuditRedactor.class);

    private final JsonMapper jsonMapper;

    public AuditRedactor(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** True if a value stored under this key must never be audited. */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalised = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SENSITIVE_EXACT.contains(normalised)) {
            return true;
        }
        for (String fragment : SENSITIVE_FRAGMENTS) {
            if (normalised.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return a redacted map for the audit column, or null for null. Scalars and lists are wrapped as
     *     {@code {"value": ...}}.
     */
    public Map<String, Object> redactToMap(Object value) {
        if (value == null) {
            return null;
        }
        Object plain = toPlain(value);
        Object redacted = redact(null, plain, 0);
        if (redacted instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", redacted);
        return wrapped;
    }

    private Object toPlain(Object value) {
        if (value instanceof Map || value instanceof Collection || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        try {
            return jsonMapper.convertValue(value, Object.class);
        } catch (RuntimeException e) {
            // Never store an unredacted fallback: record only what kind of object it was.
            log.warn("Audit value of type {} could not be converted for redaction", value.getClass().getName());
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put("_unserializable", value.getClass().getSimpleName());
            return placeholder;
        }
    }

    private Object redact(String key, Object value, int depth) {
        if (key != null && isSensitiveKey(key) && value != null) {
            return REDACTED;
        }
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return "[TRUNCATED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), redact(String.valueOf(k), v, depth + 1)));
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object element : collection) {
                out.add(redact(null, element, depth + 1));
            }
            return out;
        }
        if (value instanceof CharSequence text) {
            return redactString(text.toString());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return redact(key, toPlain(value), depth + 1);
    }

    private static String redactString(String text) {
        if (JWT.matcher(text).matches()
                || BEARER.matcher(text).matches()
                || text.regionMatches(true, 0, "otpauth://", 0, 10)
                || BASE64URL_256.matcher(text).matches()
                || SHA256_HEX.matcher(text).matches()) {
            return REDACTED;
        }
        return text.length() > MAX_STRING ? text.substring(0, MAX_STRING) + "...[truncated]" : text;
    }
}
