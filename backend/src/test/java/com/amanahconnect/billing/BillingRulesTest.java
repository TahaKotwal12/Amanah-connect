package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BillingRulesTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    // ---- amount in words ---------------------------------------------------------------------------------------

    @Test
    void rupeesUseIndianGrouping() {
        assertThat(AmountInWords.of(d("1"), "INR")).isEqualTo("Rupees One Only");
        assertThat(AmountInWords.of(d("21"), "INR")).isEqualTo("Rupees Twenty-One Only");
        assertThat(AmountInWords.of(d("100"), "INR")).isEqualTo("Rupees One Hundred Only");
        assertThat(AmountInWords.of(d("1250.50"), "INR")).isEqualTo("Rupees One Thousand Two Hundred Fifty and Fifty Paise Only");
        assertThat(AmountInWords.of(d("100000"), "INR")).isEqualTo("Rupees One Lakh Only");
        assertThat(AmountInWords.of(d("1234567.05"), "INR")).isEqualTo("Rupees Twelve Lakh Thirty-Four Thousand Five Hundred Sixty-Seven and Five Paise Only");
        assertThat(AmountInWords.of(d("10000000"), "INR")).isEqualTo("Rupees One Crore Only");
        assertThat(AmountInWords.of(d("123456789"), "INR")).isEqualTo("Rupees Twelve Crore Thirty-Four Lakh Fifty-Six Thousand Seven Hundred Eighty-Nine Only");
        assertThat(AmountInWords.of(d("9999999999.99"), "INR")).startsWith("Rupees Nine Hundred Ninety-Nine Crore Ninety-Nine Lakh Ninety-Nine Thousand Nine Hundred Ninety-Nine and Ninety-Nine Paise");
        assertThat(AmountInWords.of(d("0.50"), "INR")).isEqualTo("Rupees Zero and Fifty Paise Only");
        assertThat(AmountInWords.of(d("1000"), "INR")).isEqualTo("Rupees One Thousand Only");
        assertThat(AmountInWords.of(d("100100"), "INR")).isEqualTo("Rupees One Lakh One Hundred Only");
    }

    @Test
    void otherCurrenciesUseWesternGroupingAndTheCode() {
        assertThat(AmountInWords.of(d("1200.05"), "USD")).isEqualTo("USD One Thousand Two Hundred and 05/100 Only");
        assertThat(AmountInWords.of(d("2500000"), "USD")).isEqualTo("USD Two Million Five Hundred Thousand Only");
        assertThat(AmountInWords.of(d("12"), "EUR")).isEqualTo("EUR Twelve Only");
    }

    @Test
    void aNegativeAmountHasNoWords() {
        assertThatThrownBy(() -> AmountInWords.of(d("-1"), "INR")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- UPI link --------------------------------------------------------------------------------------------------

    @Test
    void theUpiLinkHasTheStandardShape() {
        String link = UpiLinks.build("lotus.residents@okhdfcbank", "Lotus Residents Welfare Assn.", Money.of(d("1250.5")), "INV-2026-27/000123");

        assertThat(link).isEqualTo("upi://pay?pa=lotus.residents@okhdfcbank&pn=Lotus%20Residents%20Welfare%20Assn.&am=1250.50&cu=INR&tn=INV-2026-27%2F000123");
    }

    @Test
    void theUpiLinkEncodesSpecialCharactersAndRefusesBadInput() {
        assertThat(UpiLinks.build("ab@bank", "Ram & Sons (Pune)", Money.of(d("10")), "x y")).contains("pn=Ram%20%26%20Sons%20%28Pune%29").endsWith("tn=x%20y");
        assertThat(UpiLinks.build("ab@bank", "नमस्ते", Money.of(d("10")), "n")).contains("pn=%E0%A4%A8");
        assertThat(UpiLinks.build("ab@bank", "P", Money.of(d("10")), "n".repeat(80)).substring(UpiLinks.build("ab@bank", "P", Money.of(d("10")), "").length())).hasSize(UpiLinks.NOTE_MAX);
        assertThat(UpiLinks.build("ab@bank", "P", Money.of(d("0.01")), "n")).contains("am=0.01");
        assertThatThrownBy(() -> UpiLinks.build("no-at-sign", "P", Money.of(d("1")), "n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpiLinks.build("a&pn=evil@bank", "P", Money.of(d("1")), "n")).as("no parameter injection through the VPA").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpiLinks.build("ab@bank", " ", Money.of(d("1")), "n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpiLinks.build("ab@bank", "P", Money.ZERO, "n")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- invoice status ---------------------------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test
    void statusIsDerivedFromAmountsAndDueDate() {
        assertThat(InvoiceStatusRules.derive(d("1000"), d("0"), TODAY.plusDays(1), TODAY)).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(InvoiceStatusRules.derive(d("1000"), d("0"), TODAY, TODAY)).as("on the due date it is not late").isEqualTo(InvoiceStatus.ISSUED);
        assertThat(InvoiceStatusRules.derive(d("1000"), d("0"), TODAY.minusDays(1), TODAY)).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(InvoiceStatusRules.derive(d("1000"), d("400"), TODAY, TODAY)).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(InvoiceStatusRules.derive(d("1000"), d("400"), TODAY.minusDays(1), TODAY)).as("late and partly paid is overdue").isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(InvoiceStatusRules.derive(d("1000"), d("1000"), TODAY.minusDays(30), TODAY)).as("paid in full is never overdue").isEqualTo(InvoiceStatus.PAID);
        assertThat(InvoiceStatusRules.derive(d("1000.00"), d("999.99"), TODAY.plusDays(5), TODAY)).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(InvoiceStatusRules.balance(d("1000"), d("250.25"))).isEqualTo(Money.of(d("749.75")));
    }

    @Test
    void onlyOpenInvoicesTakePayments() {
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.ISSUED)).isTrue();
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.PARTIAL)).isTrue();
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.OVERDUE)).isTrue();
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.PAID)).isFalse();
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.DRAFT)).isFalse();
        assertThat(InvoiceStatusRules.acceptsPayments(InvoiceStatus.CANCELLED)).isFalse();
    }

    // ---- periods ---------------------------------------------------------------------------------------------------

    @Test
    void currentPeriods() {
        LocalDate d = LocalDate.of(2026, 11, 20);
        assertThat(FeePeriods.current(FeeFrequency.MONTHLY, d, 4)).isEqualTo(new FeePeriods.Period("2026-11", LocalDate.of(2026, 11, 1)));
        assertThat(FeePeriods.current(FeeFrequency.QUARTERLY, d, 4)).isEqualTo(new FeePeriods.Period("2026-Q4", LocalDate.of(2026, 10, 1)));
        assertThat(FeePeriods.current(FeeFrequency.YEARLY, d, 4)).isEqualTo(new FeePeriods.Period("2026-27", LocalDate.of(2026, 4, 1)));
        assertThat(FeePeriods.current(FeeFrequency.YEARLY, LocalDate.of(2027, 2, 1), 4)).isEqualTo(new FeePeriods.Period("2026-27", LocalDate.of(2026, 4, 1)));
        assertThat(FeePeriods.current(FeeFrequency.YEARLY, d, 1)).isEqualTo(new FeePeriods.Period("2026", LocalDate.of(2026, 1, 1)));
        assertThat(FeePeriods.current(FeeFrequency.QUARTERLY, LocalDate.of(2026, 3, 31), 4).label()).isEqualTo("2026-Q1");
        assertThat(FeePeriods.current(FeeFrequency.ONE_TIME, d, 4)).isNull();
    }

    @Test
    void parsingAndDueDates() {
        assertThat(FeePeriods.parse(FeeFrequency.MONTHLY, "2026-10", 4).start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(FeePeriods.parse(FeeFrequency.QUARTERLY, "2026-Q2", 4).start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(FeePeriods.parse(FeeFrequency.YEARLY, "2026-27", 4).start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(FeePeriods.parse(FeeFrequency.YEARLY, "2026", 1).start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(FeePeriods.parse(FeeFrequency.ONE_TIME, "Diwali 2026", 4)).isEqualTo(new FeePeriods.Period("Diwali 2026", null));

        for (Object[] bad : new Object[][] {
            {FeeFrequency.MONTHLY, "2026-13"}, {FeeFrequency.MONTHLY, "2026-1"}, {FeeFrequency.MONTHLY, "Oct 2026"},
            {FeeFrequency.QUARTERLY, "2026-Q5"}, {FeeFrequency.QUARTERLY, "2026-10"},
            {FeeFrequency.YEARLY, "2026-28"}, {FeeFrequency.YEARLY, "2026"}, {FeeFrequency.ONE_TIME, ""}, {FeeFrequency.ONE_TIME, "x".repeat(31)}, {FeeFrequency.ONE_TIME, "<b>"}
        }) {
            assertThatThrownBy(() -> FeePeriods.parse((FeeFrequency) bad[0], (String) bad[1], 4)).as(bad[0] + " " + bad[1]).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> FeePeriods.parse(FeeFrequency.YEARLY, "2026-27", 1)).as("calendar-year community").isInstanceOf(ApiException.class);

        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.MONTHLY, "2026-10", 4), (short) 5)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.MONTHLY, "2026-02", 4), (short) 28)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.MONTHLY, "2026-10", 4), null)).as("default due day").isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.QUARTERLY, "2026-Q4", 4), (short) 15)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.YEARLY, "2026-27", 4), (short) 1)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(FeePeriods.dueDate(FeePeriods.parse(FeeFrequency.ONE_TIME, "Event", 4), (short) 5)).isNull();
    }
}
