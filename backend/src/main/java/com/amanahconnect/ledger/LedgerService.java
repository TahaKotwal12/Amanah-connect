package com.amanahconnect.ledger;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.file.StorageProperties;
import com.amanahconnect.ledger.LedgerDtos.AttachmentUploadRequest;
import com.amanahconnect.ledger.LedgerDtos.AttachmentUploadView;
import com.amanahconnect.ledger.LedgerDtos.CategoryView;
import com.amanahconnect.ledger.LedgerDtos.CreateCategoryRequest;
import com.amanahconnect.ledger.LedgerDtos.CreateEntryRequest;
import com.amanahconnect.ledger.LedgerDtos.EntryView;
import com.amanahconnect.ledger.LedgerDtos.ReverseEntryRequest;
import com.amanahconnect.ledger.LedgerDtos.SummaryView;
import com.amanahconnect.ledger.LedgerDtos.UpdateCategoryRequest;
import com.amanahconnect.ledger.LedgerDtos.UpdateEntryRequest;
import com.amanahconnect.ledger.LedgerQueries.EntryFilter;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categories and manual entries. Entries are never deleted or have their amount changed: a mistake is reversed (a negative
 * twin with a reason) and the right entry added. Entries that a payment produced are read-only here.
 */
@Service
@Transactional
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Map<String, String> ATTACHMENT_TYPES = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "application/pdf", "pdf");

    private final LedgerCategoryRepository categories;
    private final LedgerEntryRepository entries;
    private final LedgerQueries queries;
    private final CommunityRepository communities;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final EntityManager em;

    public LedgerService(
            LedgerCategoryRepository categories,
            LedgerEntryRepository entries,
            LedgerQueries queries,
            CommunityRepository communities,
            ObjectStorage storage,
            StorageProperties storageProperties,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock,
            EntityManager em) {
        this.categories = categories;
        this.entries = entries;
        this.queries = queries;
        this.communities = communities;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
        this.em = em;
    }

    // ---- categories -------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CategoryView> categories(UUID communityId, boolean includeInactive) {
        return categories.findByCommunityIdOrderByTypeAscNameAsc(communityId).stream()
                .filter(c -> includeInactive || c.isActive())
                .map(c -> categoryView(communityId, c)).toList();
    }

    public CategoryView createCategory(UUID communityId, CreateCategoryRequest request) {
        String name = Text.singleLine(request.name());
        if (name.isEmpty()) {
            throw invalid("name", "must not be blank");
        }
        if (categories.findByCommunityIdAndNameAndType(communityId, name, request.type()).isPresent()) {
            throw new ApiException(ErrorCode.CATEGORY_EXISTS, "There is already " + request.type().name().toLowerCase() + " category '" + name + "'.");
        }
        LedgerCategory category = new LedgerCategory();
        category.setCommunityId(communityId);
        category.setName(name);
        category.setType(request.type());
        categories.save(category);
        audit.record("LEDGER_CATEGORY_CREATED", "LedgerCategory", category.getId(), null, Map.of("name", name, "type", request.type().name()));
        return categoryView(communityId, category);
    }

    public CategoryView updateCategory(UUID communityId, UUID id, UpdateCategoryRequest request) {
        LedgerCategory category = tenantGuard.found(categories.findByIdAndCommunityId(id, communityId));
        Map<String, Object> before = Map.of("name", category.getName(), "active", category.isActive());
        if (request.name() != null) {
            String name = Text.singleLine(request.name());
            if (name.isEmpty()) throw invalid("name", "must not be blank");
            categories.findByCommunityIdAndNameAndType(communityId, name, category.getType()).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
                throw new ApiException(ErrorCode.CATEGORY_EXISTS, "There is already " + category.getType().name().toLowerCase() + " category '" + name + "'.");
            });
            category.setName(name);
        }
        if (request.active() != null && request.active() != category.isActive()) {
            if (!request.active() && category.getSystemKey() != null) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "'" + category.getName() + "' is used for payments and cannot be hidden. You can rename it.");
            }
            category.setActive(request.active());
        }
        categories.save(category);
        audit.record("LEDGER_CATEGORY_UPDATED", "LedgerCategory", id, before, Map.of("name", category.getName(), "active", category.isActive()));
        return categoryView(communityId, category);
    }

    // ---- entries ------------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<EntryView> list(UUID communityId, EntryFilter filter, Pageable page) {
        return queries.search(communityId, filter, page);
    }

    @Transactional(readOnly = true)
    public EntryView get(UUID communityId, UUID id) {
        return tenantGuard.found(queries.find(communityId, id));
    }

    public EntryView create(UUID communityId, CreateEntryRequest request) {
        LedgerCategory category = usableCategory(communityId, request.categoryId(), request.type());
        LocalDate today = LocalDate.now(clock.withZone(IST));
        checkDate(request.entryDate(), today);
        LedgerEntry entry = new LedgerEntry();
        entry.setCommunityId(communityId);
        entry.setType(request.type());
        entry.setCategory(category);
        entry.setAmount(Money.of(request.amount()).amount());
        entry.setEntryDate(request.entryDate());
        entry.setTitle(Text.singleLine(request.title()));
        entry.setNotes(Text.blankToNull(request.notes() == null ? null : request.notes().trim()));
        entry.setSource(LedgerSource.MANUAL);
        entry.setCreatedBy(AuditService.currentActorId());
        if (request.attachmentKey() != null && !request.attachmentKey().isBlank()) {
            entry.setAttachmentKey(acceptAttachment(communityId, request.attachmentKey().trim()));
        }
        entries.save(entry);
        em.flush();
        audit.record("LEDGER_ENTRY_CREATED", "LedgerEntry", entry.getId(), null, snapshot(entry));
        return get(communityId, entry.getId());
    }

    public EntryView update(UUID communityId, UUID id, UpdateEntryRequest request) {
        LedgerEntry entry = editable(communityId, id);
        Map<String, Object> before = snapshot(entry);
        if (request.categoryId() != null && !request.categoryId().equals(entry.getCategory().getId())) {
            entry.setCategory(usableCategory(communityId, request.categoryId(), entry.getType()));
        }
        if (request.entryDate() != null) {
            checkDate(request.entryDate(), LocalDate.now(clock.withZone(IST)));
            entry.setEntryDate(request.entryDate());
        }
        if (request.title() != null) entry.setTitle(Text.singleLine(request.title()));
        if (request.notes() != null) entry.setNotes(Text.blankToNull(request.notes().trim()));
        if (request.attachmentKey() != null) {
            String old = entry.getAttachmentKey();
            if (request.attachmentKey().isBlank()) {
                entry.setAttachmentKey(null);
                if (old != null) deleteQuietly(old);
            } else if (!request.attachmentKey().trim().equals(old)) {
                entry.setAttachmentKey(acceptAttachment(communityId, request.attachmentKey().trim()));
                if (old != null) deleteQuietly(old);
            }
        }
        entries.save(entry);
        em.flush();
        audit.record("LEDGER_ENTRY_UPDATED", "LedgerEntry", id, before, snapshot(entry));
        return get(communityId, id);
    }

    /** Reverses a manual entry: a negative twin (same type and category) that nets it to zero. The original stays. */
    public EntryView reverse(UUID communityId, UUID id, ReverseEntryRequest request) {
        LedgerEntry original = tenantGuard.found(entries.findWithLockByIdAndCommunityId(id, communityId));
        if (original.getSource() == LedgerSource.PAYMENT) {
            throw new ApiException(ErrorCode.LEDGER_ENTRY_READ_ONLY, "This entry came from a payment. Reverse the payment instead (POST /community/payments/{id}/reverse).");
        }
        if (original.getReversedOf() != null) {
            throw new ApiException(ErrorCode.ALREADY_REVERSED, "This entry is itself a reversal and cannot be reversed.");
        }
        if (entries.existsByCommunityIdAndReversedOfId(communityId, id)) {
            throw new ApiException(ErrorCode.ALREADY_REVERSED, "This entry was already reversed.");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        LocalDate date = request.entryDate() == null ? today : request.entryDate();
        checkDate(date, today);
        if (date.isBefore(original.getEntryDate())) {
            throw invalid("entryDate", "cannot be before the entry it reverses (" + original.getEntryDate() + ")");
        }
        LedgerEntry reversal = new LedgerEntry();
        reversal.setCommunityId(communityId);
        reversal.setType(original.getType());
        reversal.setCategory(original.getCategory());
        reversal.setAmount(original.getAmount().negate());
        reversal.setEntryDate(date);
        String title = "Reversal: " + original.getTitle();
        reversal.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        reversal.setNotes(request.reason().trim());
        reversal.setSource(LedgerSource.MANUAL);
        reversal.setReversedOf(original);
        reversal.setReversalReason(request.reason().trim());
        reversal.setCreatedBy(AuditService.currentActorId());
        entries.save(reversal);
        em.flush();
        Map<String, Object> after = snapshot(reversal);
        after.put("reason", request.reason().trim());
        audit.record("LEDGER_ENTRY_REVERSED", "LedgerEntry", reversal.getId(), Map.of("entryId", id.toString()), after);
        return get(communityId, reversal.getId());
    }

    @Transactional(readOnly = true)
    public SummaryView summary(UUID communityId, LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid("to", "cannot be before from");
        }
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        return queries.summary(communityId, from, to, community.getOpeningBalance());
    }

    // ---- attachments -----------------------------------------------------------------------------------------------------------

    public AttachmentUploadView attachmentUploadUrl(UUID communityId, AttachmentUploadRequest request) {
        String type = request.contentType().trim().toLowerCase();
        String extension = ATTACHMENT_TYPES.get(type);
        if (extension == null) {
            throw invalid("contentType", "must be image/png, image/jpeg, image/webp or application/pdf");
        }
        long max = storageProperties.attachmentMaxBytes();
        if (request.sizeBytes() == null || request.sizeBytes() < 1 || request.sizeBytes() > max) {
            throw invalid("sizeBytes", "must be between 1 and " + max + " bytes");
        }
        String key = attachmentPrefix(communityId) + UUID.randomUUID() + "." + extension;
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, request.sizeBytes());
        audit.record("LEDGER_ATTACHMENT_UPLOAD_REQUESTED", "LedgerEntry", null, null, Map.of("attachmentKey", key));
        return new AttachmentUploadView(upload.url(), "PUT", upload.headers(), key, upload.expiresAt(), max);
    }

    private String acceptAttachment(UUID communityId, String key) {
        if (!key.startsWith(attachmentPrefix(communityId)) || !key.matches("^communities/[0-9a-f-]{36}/ledger/[0-9a-f-]{36}\\.(png|jpg|webp|pdf)$")) {
            throw invalid("attachmentKey", "not an attachment uploaded for this community");
        }
        var info = storage.head(key);
        if (info.isEmpty()) {
            throw invalid("attachmentKey", "the file has not been uploaded");
        }
        if (!ATTACHMENT_TYPES.containsKey(info.get().contentType()) || info.get().size() > storageProperties.attachmentMaxBytes()) {
            deleteQuietly(key);
            throw invalid("attachmentKey", "the uploaded file is not acceptable (PNG, JPEG, WebP or PDF, at most " + storageProperties.attachmentMaxBytes() + " bytes)");
        }
        return key;
    }

    private static String attachmentPrefix(UUID communityId) {
        return "communities/" + communityId + "/ledger/";
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete storage object {}: {}", key, e.toString());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private LedgerEntry editable(UUID communityId, UUID id) {
        LedgerEntry entry = tenantGuard.found(entries.findWithLockByIdAndCommunityId(id, communityId));
        if (entry.getSource() == LedgerSource.PAYMENT) {
            throw new ApiException(ErrorCode.LEDGER_ENTRY_READ_ONLY, "This entry came from a payment and is read-only. Reverse the payment to correct it.");
        }
        if (entry.getReversedOf() != null || entries.existsByCommunityIdAndReversedOfId(communityId, id)) {
            throw new ApiException(ErrorCode.LEDGER_ENTRY_READ_ONLY, "This entry is part of a reversal and cannot be edited.");
        }
        return entry;
    }

    private LedgerCategory usableCategory(UUID communityId, UUID categoryId, LedgerType type) {
        // Another community's category and a missing one are both "unknown".
        LedgerCategory category = categories.findByIdAndCommunityId(categoryId, communityId).orElseThrow(() -> invalid("categoryId", "unknown category"));
        if (category.getType() != type) {
            throw invalid("categoryId", "this is an " + category.getType().name().toLowerCase() + " category, but the entry is " + type.name().toLowerCase());
        }
        if (!category.isActive()) {
            throw invalid("categoryId", "this category is hidden");
        }
        return category;
    }

    private CategoryView categoryView(UUID communityId, LedgerCategory c) {
        return new CategoryView(c.getId(), c.getName(), c.getType(), c.isActive(), c.getSystemKey() != null, entries.countByCommunityIdAndCategoryId(communityId, c.getId()));
    }

    private static void checkDate(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) throw invalid("entryDate", "cannot be in the future");
        if (date.isBefore(today.minusYears(25))) throw invalid("entryDate", "too far in the past");
    }

    private static Map<String, Object> snapshot(LedgerEntry e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", e.getType() == null ? null : e.getType().name());
        map.put("category", e.getCategory() == null ? null : e.getCategory().getName());
        map.put("amount", e.getAmount() == null ? null : Money.of(e.getAmount()).toString());
        map.put("entryDate", e.getEntryDate() == null ? null : e.getEntryDate().toString());
        map.put("title", e.getTitle());
        map.put("hasAttachment", e.getAttachmentKey() != null);
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
