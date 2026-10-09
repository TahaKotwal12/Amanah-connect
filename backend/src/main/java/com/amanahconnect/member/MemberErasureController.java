package com.amanahconnect.member;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/members")
@Tag(name = "Community · Members", description = "Members of the community (COMMUNITY_ADMIN).")
public class MemberErasureController {

    private final MemberErasure erasure;

    public MemberErasureController(MemberErasure erasure) {
        this.erasure = erasure;
    }

    @PostMapping("/{id}/anonymise")
    @AuditHandledBy("MemberErasure records MEMBER_ANONYMISED")
    @Operation(summary = "Erase a member's personal data (right to erasure)",
            description = "Irreversible. Needs memberNo (the member's number, typed back to confirm) and a reason (do not put personal details in it: the audit trail cannot be edited). "
                    + "Removes the name, email, phone, group, custom fields, the registration form, the free text of their complaints, the names in emails sent to them and the stored receipt PDFs. "
                    + "Keeps the member's number and every invoice, payment, receipt and ledger entry unchanged, so the books still add up. The member shows as \"Erased member\". The answer says what was kept and removed. "
                    + "A member already erased is a 409.")
    public MemberErasure.Result anonymise(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody MemberErasure.Request body) {
        return erasure.anonymise(communityId, id, body);
    }
}
