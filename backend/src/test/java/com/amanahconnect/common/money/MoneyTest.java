package com.amanahconnect.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class MoneyTest {

    private final JsonMapper json = JsonMapper.builder().addModule(new MoneyJacksonConfig().moneyAsStringModule()).build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // ---- rounding ---------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "10.005, 10.01", "10.004, 10.00", "10.015, 10.02", "2.675, 2.68", "0.005, 0.01", "0.004, 0.00",
        "-10.005, -10.01", "-10.004, -10.00", "-0.005, -0.01", "1234.5, 1234.50", "7, 7.00", "0, 0.00"})
    void roundsHalfUpToTwoDecimals(String input, String expected) {
        Money money = Money.of(new BigDecimal(input));

        assertThat(money.toString()).isEqualTo(expected);
        assertThat(money.amount().scale()).isEqualTo(2);
    }

    @Test
    void equalAmountsAreEqualWhateverTheirInputScale() {
        assertThat(Money.of(new BigDecimal("10"))).isEqualTo(Money.parse("10.00")).hasSameHashCodeAs(Money.parse("10.0"));
        assertThat(Money.parse("10.01")).isNotEqualTo(Money.parse("10.02"));
    }

    @Test
    void neverUsesScientificNotation() {
        assertThat(Money.of(new BigDecimal("1E+3")).toString()).isEqualTo("1000.00");
        assertThat(Money.of(new BigDecimal("1E-2")).toString()).isEqualTo("0.01");
    }

    // ---- strict parsing ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.5", "10", "10.5", "10.50", "-3.25", "999999999999.99", " 12.30 ", "000012.30"})
    void parsesPlainAmountsWithAtMostTwoDecimals(String text) {
        assertThat(Money.parse(text).amount().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "10.505", "10.", ".5", "1,000.00", "1e3", "1E+3", "--5", "+5", "10 00", "1000000000000", "NaN", "Infinity", "0x10", "١٢٣"})
    void rejectsAnythingThatIsNotAPlainAmount(String text) {
        assertThatThrownBy(() -> Money.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAndOutOfRange() {
        assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(new BigDecimal("1000000000000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1000000000000"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.of(new BigDecimal("999999999999.994")).toString()).isEqualTo("999999999999.99");
    }

    // ---- arithmetic -------------------------------------------------------------------------

    @Test
    void doesExactDecimalArithmeticWithoutFloatingPointDrift() {
        Money total = Money.parse("0.10").plus(Money.parse("0.20"));

        assertThat(total).isEqualTo(Money.parse("0.30")); // 0.1 + 0.2 != 0.3 in floating point

        Money sum = Money.ZERO;
        for (int i = 0; i < 1000; i++) {
            sum = sum.plus(Money.parse("0.10"));
        }
        assertThat(sum.toString()).isEqualTo("100.00");
    }

    @Test
    void multipliesAndRoundsHalfUp() {
        assertThat(Money.parse("1000.00").times(new BigDecimal("0.18")).toString()).isEqualTo("180.00");
        assertThat(Money.parse("99.99").times(new BigDecimal("0.18")).toString()).isEqualTo("18.00"); // 17.9982
        assertThat(Money.parse("0.05").times(new BigDecimal("0.5")).toString()).isEqualTo("0.03"); // 0.025 -> half up
    }

    @Test
    void comparesAndSignsAndNegates() {
        assertThat(Money.parse("5").compareTo(Money.parse("4.99"))).isPositive();
        assertThat(Money.parse("5")).isGreaterThan(Money.ZERO);
        assertThat(Money.parse("5").isPositive()).isTrue();
        assertThat(Money.parse("-5").isNegative()).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.parse("12.34").negate().toString()).isEqualTo("-12.34");
        assertThat(Money.parse("10").minus(Money.parse("12.50")).toString()).isEqualTo("-2.50");
    }

    // ---- JSON -------------------------------------------------------------------------------

    @Test
    void serialisesMoneyAndBigDecimalAsStrings() {
        record Payload(Money money, BigDecimal decimal, int count) {}

        String body = json.writeValueAsString(new Payload(Money.parse("1250.5"), new BigDecimal("1E+3"), 3));

        assertThat(body).isEqualTo("{\"money\":\"1250.50\",\"decimal\":\"1000\",\"count\":3}");
    }

    @Test
    void deserialisesMoneyFromAStringAndNormalisesIt() {
        assertThat(json.readValue("\"10.5\"", Money.class)).isEqualTo(Money.parse("10.50"));
        assertThat(json.readValue("\"-3\"", Money.class).toString()).isEqualTo("-3.00");
        assertThatThrownBy(() -> json.readValue("\"10.505\"", Money.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("\"abc\"", Money.class)).isInstanceOf(RuntimeException.class);
    }

    // ---- @MoneyAmount -----------------------------------------------------------------------

    record Input(@MoneyAmount BigDecimal plain, @MoneyAmount(positive = true) BigDecimal positive, @MoneyAmount(nonNegative = true) String text, @MoneyAmount Money money) {}

    private Set<String> violations(Input input) {
        return validator.validate(input).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void validatorAcceptsGoodAmountsAndNull() {
        assertThat(violations(new Input(null, null, null, null))).isEmpty();
        assertThat(violations(new Input(new BigDecimal("10.50"), new BigDecimal("0.01"), "0", Money.parse("5")))).isEmpty();
        assertThat(violations(new Input(new BigDecimal("10.500"), BigDecimal.ONE, "12.30", null))).as("trailing zeros are fine").isEmpty();
    }

    @Test
    void validatorRejectsExtraDecimalsGarbageZeroNegativesAndHugeValues() {
        assertThat(violations(new Input(new BigDecimal("10.505"), null, null, null))).containsExactly("plain: must have at most 2 decimals");
        assertThat(violations(new Input(null, BigDecimal.ZERO, null, null))).containsExactly("positive: must be greater than zero");
        assertThat(violations(new Input(null, new BigDecimal("-1"), null, null))).containsExactly("positive: must be greater than zero");
        assertThat(violations(new Input(null, null, "-0.01", null))).containsExactly("text: must not be negative");
        assertThat(violations(new Input(null, null, "abc", null))).containsExactly("text: must be a valid amount, for example 1250.50");
        assertThat(violations(new Input(new BigDecimal("1000000000000"), null, null, null))).containsExactly("plain: is too large");
        assertThat(violations(new Input(new BigDecimal("1E+3"), null, null, null))).as("1E+3 is just 1000").isEmpty();
    }
}
