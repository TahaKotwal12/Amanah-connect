package com.amanahconnect.support.tenant;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.money.MoneyAmount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Request/response bodies of the test-only endpoints. Note: no community id in any request. */
public final class TestSupportDtos {

    private TestSupportDtos() {}

    public record MemberView(UUID id, String memberNo, String fullName) {}

    public record MemberRequest(@NotBlank @Size(max = 150) String fullName) {}

    public record WhoAmI(UUID communityIdParam, UUID communityIdFromTenant, UUID userId, String status) {}

    public record MoneyView(Money money, BigDecimal decimal) {}

    public record MoneyInput(
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @MoneyAmount(nonNegative = true) String text) {}
}
