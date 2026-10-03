package com.amanahconnect.community.settings;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.community.settings.SettingsDtos.LogoUploadRequest;
import com.amanahconnect.community.settings.SettingsDtos.LogoUploadView;
import com.amanahconnect.community.settings.SettingsDtos.SettingsView;
import com.amanahconnect.community.settings.SettingsDtos.UpdateSettingsRequest;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/settings")
@Tag(name = "Community · Settings", description = "The community's profile, finance basics, logo and notification preferences (COMMUNITY_ADMIN).")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get settings")
    public SettingsView get(@CurrentCommunity UUID communityId) {
        return service.get(communityId);
    }

    @PatchMapping
    @AuditHandledBy("SettingsService records COMMUNITY_SETTINGS_UPDATED with before and after")
    @Operation(summary = "Update settings", description = "Partial update. Currency and financial year start month are locked once invoices, payments or ledger entries exist. A logo is set by uploading to the URL from /settings/logo/upload-url and then sending its logoKey here.")
    public SettingsView update(@CurrentCommunity UUID communityId, @Valid @RequestBody UpdateSettingsRequest body) {
        return service.update(communityId, body);
    }

    @PostMapping("/logo/upload-url")
    @AuditHandledBy("SettingsService records COMMUNITY_LOGO_UPLOAD_REQUESTED")
    @Operation(summary = "Get a signed URL to upload the logo", description = "PNG, JPEG or WebP up to the configured size. PUT the file to uploadUrl with exactly the returned headers, then PATCH /settings with logoKey.")
    public LogoUploadView logoUploadUrl(@CurrentCommunity UUID communityId, @Valid @RequestBody LogoUploadRequest body) {
        return service.logoUploadUrl(communityId, body);
    }

    @DeleteMapping("/logo")
    @AuditHandledBy("SettingsService records COMMUNITY_LOGO_REMOVED")
    @Operation(summary = "Remove the logo")
    public SettingsView removeLogo(@CurrentCommunity UUID communityId) {
        return service.removeLogo(communityId);
    }
}
