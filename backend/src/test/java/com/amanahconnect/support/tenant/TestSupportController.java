package com.amanahconnect.support.tenant;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.support.tenant.TestSupportDtos.MemberRequest;
import com.amanahconnect.support.tenant.TestSupportDtos.MemberView;
import com.amanahconnect.support.tenant.TestSupportDtos.MoneyInput;
import com.amanahconnect.support.tenant.TestSupportDtos.MoneyView;
import com.amanahconnect.support.tenant.TestSupportDtos.WhoAmI;
import com.amanahconnect.tenant.CurrentCommunity;
import com.amanahconnect.tenant.CurrentTenant;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints under /community/test-support (they exist only on the test classpath). They behave
 * like a real tenant module so the cross-tenant harness, the filter, auditing, paging and money
 * handling can be tested end to end through real security and real HTTP.
 */
@RestController
@RequestMapping("/api/v1/community/test-support")
public class TestSupportController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("name", "fullName", "number", "memberNo", "created", "createdAt"));

    private final TestSupportService service;

    public TestSupportController(TestSupportService service) {
        this.service = service;
    }

    @GetMapping("/whoami")
    public WhoAmI whoami(@CurrentCommunity UUID communityId, @CurrentCommunity CurrentTenant tenant) {
        return new WhoAmI(communityId, tenant.communityId(), tenant.userId(), tenant.status());
    }

    @GetMapping("/members/{id}")
    public MemberView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @GetMapping("/members")
    public PageResponse<MemberView> list(@CurrentCommunity UUID communityId, @Valid PageQuery query) {
        return service.list(communityId, SORT.toPageRequest(query));
    }

    @PostMapping("/members")
    public ResponseEntity<MemberView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody MemberRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body.fullName()));
    }

    @PutMapping("/members/{id}")
    public MemberView rename(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody MemberRequest body) {
        return service.rename(communityId, id, body.fullName());
    }

    @DeleteMapping("/members/{id}")
    public MemberView delete(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.softDelete(communityId, id);
    }

    @PutMapping("/fail-after-save/{id}")
    public MemberView failAfterSave(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody MemberRequest body) {
        return service.renameThenFail(communityId, id, body.fullName());
    }

    @PutMapping("/failing-audit/{id}")
    public MemberView failingAudit(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody MemberRequest body) {
        return service.renameWithFailingAudit(communityId, id, body.fullName());
    }

    @GetMapping("/unscoped-members/{id}")
    public MemberView unscoped(@PathVariable UUID id) {
        return service.unscopedGet(id);
    }

    @GetMapping("/money")
    public MoneyView money() {
        return new MoneyView(Money.of(new BigDecimal("1250.5")), new BigDecimal("1E+3"));
    }

    @PostMapping("/money")
    public MoneyView echoMoney(@Valid @RequestBody MoneyInput body) {
        return new MoneyView(Money.of(body.amount()), body.amount());
    }

    @PostMapping("/noop")
    public ResponseEntity<Void> noop() {
        return ResponseEntity.noContent().build();
    }
}
