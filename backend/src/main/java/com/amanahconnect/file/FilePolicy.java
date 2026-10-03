package com.amanahconnect.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/** What each kind of stored file allows: the content types (and their extensions), the size limit and the key folder. */
public enum FilePolicy {
    LOGO(StoredFileKind.LOGO, "logo", "a logo", types("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp"), StorageProperties::logoMaxBytes),
    LEDGER_ATTACHMENT(StoredFileKind.LEDGER_ATTACHMENT, "ledger", "an attachment", types("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "application/pdf", "pdf"), StorageProperties::attachmentMaxBytes),
    SUPPORT_ATTACHMENT(StoredFileKind.SUPPORT_ATTACHMENT, "support", "an attachment", types("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "application/pdf", "pdf"), StorageProperties::attachmentMaxBytes),
    RECEIPT_PDF(StoredFileKind.RECEIPT_PDF, "receipts", "a receipt", types("application/pdf", "pdf"), p -> 10L * 1024 * 1024);

    private final StoredFileKind kind;
    private final String folder;
    private final String noun;
    private final Map<String, String> types;
    private final ToLongFunction<StorageProperties> maxBytes;

    FilePolicy(StoredFileKind kind, String folder, String noun, Map<String, String> types, ToLongFunction<StorageProperties> maxBytes) {
        this.kind = kind;
        this.folder = folder;
        this.noun = noun;
        this.types = types;
        this.maxBytes = maxBytes;
    }

    public static FilePolicy of(StoredFileKind kind) {
        for (FilePolicy p : values()) if (p.kind == kind) return p;
        throw new IllegalArgumentException("No policy for " + kind);
    }

    public StoredFileKind kind() { return kind; }

    public String noun() { return noun; }

    public long maxBytes(StorageProperties properties) { return maxBytes.applyAsLong(properties); }

    /** The extension for a content type, or null if this kind does not allow it. */
    public String extensionFor(String contentType) { return types.get(contentType); }

    public boolean allows(String contentType) { return types.containsKey(contentType); }

    /** {@code image/png, image/jpeg or image/webp}. */
    public String allowedTypesText() {
        List<String> names = List.copyOf(types.keySet());
        return names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    /** {@code PNG, JPEG or WebP}. */
    public String friendlyTypesText() {
        List<String> names = types.keySet().stream().map(t -> switch (t) { case "image/png" -> "PNG"; case "image/jpeg" -> "JPEG"; case "image/webp" -> "WebP"; default -> "PDF"; }).toList();
        return names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    /** The keys this kind can have for a community: {@code communities/<id>/<folder>/<uuid>.<ext>}. */
    public Pattern keyPattern(java.util.UUID communityId) {
        String extensions = String.join("|", types.values().stream().distinct().toList());
        return Pattern.compile("^communities/" + communityId + "/" + folder + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(" + extensions + ")$");
    }

    public String keyPrefix(java.util.UUID communityId) {
        return "communities/" + communityId + "/" + folder + "/";
    }

    private static Map<String, String> types(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }
}
