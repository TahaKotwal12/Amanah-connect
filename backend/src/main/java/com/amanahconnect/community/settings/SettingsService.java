package com.amanahconnect.community.settings;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.settings.SettingsDtos.LogoUploadRequest;
import com.amanahconnect.community.settings.SettingsDtos.LogoUploadView;
import com.amanahconnect.community.settings.SettingsDtos.NotificationSettingsView;
import com.amanahconnect.community.settings.SettingsDtos.SettingsView;
import com.amanahconnect.community.settings.SettingsDtos.UpdateNotificationSettings;
import com.amanahconnect.community.settings.SettingsDtos.UpdateSettingsRequest;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.file.FileRejectedException;
import com.amanahconnect.file.FileService;
import com.amanahconnect.file.StoredFileKind;
import com.amanahconnect.notification.NotificationSettings;
import com.amanahconnect.notification.NotificationSettingsRepository;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The community's own profile, finance basics and notification preferences. Every call is scoped to the caller's community. */
@Service
@Transactional
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final String GROUP_LABEL_KEY = "memberGroupLabel";
    public static final String DEFAULT_GROUP_LABEL = "Group";
    private static final Pattern UPI = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.\\-_]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63}$");
    private static final Pattern PAYEE = Pattern.compile("^[\\p{L}\\p{N} .,&'()/-]{2,150}$");
    private static final Pattern GROUP_LABEL = Pattern.compile("^[\\p{L}\\p{N} .&'/-]{1,30}$");

    private final CommunityRepository communities;
    private final NotificationSettingsRepository notificationSettings;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final FileService files;
    private final AuditService audit;

    public SettingsService(
            CommunityRepository communities,
            NotificationSettingsRepository notificationSettings,
            NamedParameterJdbcTemplate jdbc,
            ObjectStorage storage,
            FileService files,
            AuditService audit) {
        this.communities = communities;
        this.notificationSettings = notificationSettings;
        this.jdbc = jdbc;
        this.storage = storage;
        this.files = files;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SettingsView get(UUID communityId) {
        return view(community(communityId), notifications(communityId));
    }

    public SettingsView update(UUID communityId, UpdateSettingsRequest request) {
        Community community = community(communityId);
        NotificationSettings notifications = notifications(communityId);
        if (request.version() != null && request.version() != community.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "The settings were changed by someone else. Reload and try again.");
        }
        Map<String, Object> before = snapshot(community, notifications);
        List<String> problems = new ArrayList<>();

        if (request.name() != null) community.setName(request.name().trim());
        if (request.contactName() != null) community.setContactName(blankToNull(request.contactName()));
        if (request.contactEmail() != null) community.setContactEmail(blankToNull(request.contactEmail()));
        if (request.contactPhone() != null) community.setContactPhone(blankToNull(request.contactPhone()));
        if (request.addressLine1() != null) community.setAddressLine1(blankToNull(request.addressLine1()));
        if (request.addressLine2() != null) community.setAddressLine2(blankToNull(request.addressLine2()));
        if (request.city() != null) community.setCity(blankToNull(request.city()));
        if (request.state() != null) community.setState(blankToNull(request.state()));
        if (request.postalCode() != null) community.setPostalCode(blankToNull(request.postalCode()));
        if (request.country() != null) community.setCountry(request.country());
        if (request.dateOfEstablishment() != null) community.setDateOfEstablishment(request.dateOfEstablishment());

        applyFinance(communityId, community, request, problems);
        applyUpi(community, request, problems);
        applyGroupLabel(community, request, problems);
        applyLogo(communityId, community, request, problems);
        if (request.openingBalance() != null) community.setOpeningBalance(com.amanahconnect.common.money.Money.of(request.openingBalance()).amount());
        applyNotifications(notifications, request.notifications());

        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", problems);
        }
        communities.saveAndFlush(community);
        notificationSettings.save(notifications);
        audit.record("COMMUNITY_SETTINGS_UPDATED", "Community", communityId, before, snapshot(community, notifications));
        return view(community, notifications);
    }

    // ---- logo ---------------------------------------------------------------------------------------

    public LogoUploadView logoUploadUrl(UUID communityId, LogoUploadRequest request) {
        FileService.Upload upload;
        try {
            upload = files.prepareUpload(communityId, StoredFileKind.LOGO, request.contentType(), request.sizeBytes());
        } catch (FileRejectedException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(e.getMessage()));
        }
        audit.record("COMMUNITY_LOGO_UPLOAD_REQUESTED", "Community", communityId, null, Map.of("logoKey", upload.key()));
        return new LogoUploadView(upload.uploadUrl(), upload.method(), upload.headers(), upload.key(), upload.expiresAt(), upload.maxBytes());
    }

    public SettingsView removeLogo(UUID communityId) {
        Community community = community(communityId);
        String old = community.getLogoKey();
        if (old != null) {
            community.setLogoKey(null);
            communities.saveAndFlush(community);
            files.delete(communityId, old);
            audit.record("COMMUNITY_LOGO_REMOVED", "Community", communityId, Map.of("logoKey", old), null);
        }
        return view(community, notifications(communityId));
    }

    private void applyLogo(UUID communityId, Community community, UpdateSettingsRequest request, List<String> problems) {
        if (request.logoKey() == null) {
            return;
        }
        String key = request.logoKey().trim();
        if (key.isEmpty()) {
            problems.add("logoKey: use DELETE /community/settings/logo to remove the logo");
            return;
        }
        try {
            files.accept(communityId, StoredFileKind.LOGO, key);
        } catch (FileRejectedException e) {
            problems.add("logoKey: " + e.getMessage());
            return;
        }
        if (!key.equals(community.getLogoKey())) {
            String old = community.getLogoKey();
            community.setLogoKey(key);
            if (old != null) files.delete(communityId, old);
        }
    }

    // ---- fields ---------------------------------------------------------------------------------------

    private void applyFinance(UUID communityId, Community community, UpdateSettingsRequest request, List<String> problems) {
        boolean currencyChanges = request.currency() != null && !request.currency().equals(community.getCurrency());
        boolean monthChanges = request.financialYearStartMonth() != null && request.financialYearStartMonth() != community.getFinancialYearStartMonth();
        if (currencyChanges) {
            try {
                Currency.getInstance(request.currency());
            } catch (IllegalArgumentException e) {
                problems.add("currency: unknown currency code");
                currencyChanges = false;
            }
        }
        if ((currencyChanges || monthChanges) && financialRecordsExist(communityId)) {
            throw new ApiException(ErrorCode.SETTING_LOCKED,
                    "The currency and financial year start month cannot change once invoices, payments or ledger entries exist.");
        }
        if (currencyChanges) community.setCurrency(request.currency());
        if (monthChanges) community.setFinancialYearStartMonth(request.financialYearStartMonth().shortValue());
    }

    private void applyUpi(Community community, UpdateSettingsRequest request, List<String> problems) {
        if (request.upiId() != null) {
            String upi = blankToNull(request.upiId());
            if (upi != null && !UPI.matcher(upi).matches()) {
                problems.add("upiId: must look like name@bank (letters, digits, '.', '-', '_' before the @)");
            } else {
                community.setUpiId(upi);
            }
        }
        if (request.upiPayeeName() != null) {
            String payee = blankToNull(request.upiPayeeName());
            if (payee != null && !PAYEE.matcher(payee).matches()) {
                problems.add("upiPayeeName: may contain letters, digits, spaces and . , & ' ( ) / - only");
            } else {
                community.setUpiPayeeName(payee);
            }
        }
        if (problems.stream().noneMatch(p -> p.startsWith("upi")) && community.getUpiId() != null && community.getUpiPayeeName() == null) {
            problems.add("upiPayeeName: required when a UPI ID is set (it is shown to the payer)");
        }
    }

    private void applyGroupLabel(Community community, UpdateSettingsRequest request, List<String> problems) {
        if (request.memberGroupLabel() == null) {
            return;
        }
        String label = request.memberGroupLabel().trim();
        Map<String, Object> settings = new LinkedHashMap<>(community.getSettings());
        if (label.isEmpty()) {
            settings.remove(GROUP_LABEL_KEY);
        } else if (!GROUP_LABEL.matcher(label).matches()) {
            problems.add("memberGroupLabel: 1 to 30 letters, digits, spaces and . & ' / -");
            return;
        } else {
            settings.put(GROUP_LABEL_KEY, label);
        }
        community.setSettings(settings);
    }

    private static void applyNotifications(NotificationSettings target, UpdateNotificationSettings update) {
        if (update == null) {
            return;
        }
        if (update.dueReminderDaysBefore() != null) target.setDueReminderDaysBefore(update.dueReminderDaysBefore());
        if (update.overdueReminderEveryDays() != null) target.setOverdueReminderEveryDays(update.overdueReminderEveryDays());
        if (update.sendWelcomeEmail() != null) target.setSendWelcome(update.sendWelcomeEmail());
        if (update.sendReceiptEmail() != null) target.setSendReceipt(update.sendReceiptEmail());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private boolean financialRecordsExist(UUID communityId) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", communityId);
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM invoices WHERE community_id = :c)"
                        + " OR EXISTS (SELECT 1 FROM payment_records WHERE community_id = :c)"
                        + " OR EXISTS (SELECT 1 FROM ledger_entries WHERE community_id = :c)",
                params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private Community community(UUID communityId) {
        return communities.findById(communityId).orElseThrow(NotFoundException::new);
    }

    private NotificationSettings notifications(UUID communityId) {
        return notificationSettings.findByCommunityId(communityId).orElseThrow(NotFoundException::new);
    }

    private SettingsView view(Community c, NotificationSettings n) {
        String logoUrl = null;
        if (c.getLogoKey() != null) {
            try {
                logoUrl = storage.presignDownload(c.getLogoKey());
            } catch (RuntimeException e) {
                log.warn("Could not sign a logo URL: {}", e.toString());
            }
        }
        Object label = c.getSettings().get(GROUP_LABEL_KEY);
        return new SettingsView(
                c.getName(), c.getContactName(), c.getContactEmail(), c.getContactPhone(), c.getAddressLine1(), c.getAddressLine2(),
                c.getCity(), c.getState(), c.getPostalCode(), c.getCountry(), c.getDateOfEstablishment(), c.getLogoKey(), logoUrl,
                c.getCurrency(), c.getFinancialYearStartMonth(), financialRecordsExist(c.getId()), c.getUpiId(), c.getUpiPayeeName(),
                label instanceof String s && !s.isBlank() ? s : DEFAULT_GROUP_LABEL,
                com.amanahconnect.common.money.Money.of(c.getOpeningBalance()),
                new NotificationSettingsView(n.getDueReminderDaysBefore(), n.getOverdueReminderEveryDays(), n.isSendWelcome(), n.isSendReceipt()),
                c.getVersion());
    }

    private Map<String, Object> snapshot(Community c, NotificationSettings n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", c.getName());
        map.put("contactName", c.getContactName());
        map.put("contactEmail", c.getContactEmail());
        map.put("contactPhone", c.getContactPhone());
        map.put("addressLine1", c.getAddressLine1());
        map.put("addressLine2", c.getAddressLine2());
        map.put("city", c.getCity());
        map.put("state", c.getState());
        map.put("postalCode", c.getPostalCode());
        map.put("country", c.getCountry());
        map.put("dateOfEstablishment", c.getDateOfEstablishment() == null ? null : c.getDateOfEstablishment().toString());
        map.put("logoKey", c.getLogoKey());
        map.put("currency", c.getCurrency());
        map.put("financialYearStartMonth", c.getFinancialYearStartMonth());
        map.put("upiId", c.getUpiId());
        map.put("upiPayeeName", c.getUpiPayeeName());
        map.put("memberGroupLabel", c.getSettings().get(GROUP_LABEL_KEY));
        map.put("openingBalance", c.getOpeningBalance().toPlainString());
        map.put("dueReminderDaysBefore", n.getDueReminderDaysBefore());
        map.put("overdueReminderEveryDays", n.getOverdueReminderEveryDays());
        map.put("sendWelcomeEmail", n.isSendWelcome());
        map.put("sendReceiptEmail", n.isSendReceipt());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
