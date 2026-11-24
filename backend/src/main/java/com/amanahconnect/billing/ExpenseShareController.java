package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.billing.ExpenseShareDtos.DivideExpenseRequest;
import com.amanahconnect.billing.ExpenseShareDtos.DivideResult;
import com.amanahconnect.billing.ExpenseShareDtos.SharesView;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/expenses")
@Tag(name = "Community · Expense shares", description = "Divide an expense among members and follow what has been collected (COMMUNITY_ADMIN).")
public class ExpenseShareController {

    private final ExpenseShareService service;

    public ExpenseShareController(ExpenseShareService service) {
        this.service = service;
    }

    @PostMapping("/{id}/divide")
    @AuditHandledBy("ExpenseShareService records EXPENSE_DIVIDED")
    @Operation(summary = "Divide an expense among members",
            description = "Splits the expense equally (to the paisa) among the given members, or every active member if none are given. Each member gets an ISSUED invoice with a gap-free number, and the bill is emailed. Refused with 409 EXPENSE_ALREADY_DIVIDED while any share is live. Cash is then recorded on each invoice with the ordinary payment endpoint, which issues the receipt.")
    public ResponseEntity<DivideResult> divide(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody DivideExpenseRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.divide(communityId, id, body));
    }

    @GetMapping("/{id}/shares")
    @Operation(summary = "The shares of an expense", description = "Each member's share (an invoice) with what has been paid, plus the totals: divided, collected and pending.")
    public SharesView shares(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.shares(communityId, id);
    }
}
