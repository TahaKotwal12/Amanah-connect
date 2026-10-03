package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MemberUnitsTest {

    // ---- custom fields ------------------------------------------------------------------------------------------

    @Test
    void acceptsFlatLowerSnakeCaseFieldsOfTextNumberAndBoolean() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("vehicle_no", "  MH12AB1234 ");
        input.put("family_size", 4);
        input.put("ratio", 1.5);
        input.put("owner", true);
        input.put("empty", "   ");
        input.put("nothing", null);

        Map<String, Object> clean = CustomFields.validate(input);

        assertThat(clean).containsEntry("vehicle_no", "MH12AB1234").containsEntry("family_size", 4).containsEntry("ratio", 1.5).containsEntry("owner", true);
        assertThat(clean).as("blank text and null mean no value").doesNotContainKeys("empty", "nothing");
        assertThat(CustomFields.validate(null)).isEmpty();
        assertThat(CustomFields.validate(Map.of())).isEmpty();
    }

    @Test
    void rejectsBadKeysValuesAndTooManyFieldsListingEveryProblem() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Bad Key", "x");
        input.put("1st", "x");
        input.put("nested", Map.of("a", 1));
        input.put("list", java.util.List.of(1));
        input.put("long", "x".repeat(501));
        input.put("control", "a\u0007b");
        input.put("nan", Double.NaN);
        input.put("<script>alert(1)</script>", "x");

        ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> CustomFields.validate(input));

        assertThat(e.details()).hasSize(8);
        assertThat(String.join("|", e.details())).doesNotContain("<script>");
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) many.put("f" + i, "v");
        assertThatThrownBy(() -> CustomFields.validate(many)).isInstanceOf(ApiException.class).hasMessageContaining("invalid");
        Map<String, Object> exactly = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) exactly.put("f" + i, "v");
        assertThat(CustomFields.validate(exactly)).hasSize(20);
    }

    // ---- member numbers ------------------------------------------------------------------------------------------

    @Test
    void thePrefixComesFromTheSlug() {
        assertThat(MemberNumbers.prefixOf("garden-society")).isEqualTo("GARDENSO");
        assertThat(MemberNumbers.prefixOf("lotus")).isEqualTo("LOTUS");
        assertThat(MemberNumbers.prefixOf("a-b-c")).isEqualTo("ABC");
        assertThat(MemberNumbers.prefixOf("---")).isEqualTo("MEM");
        assertThat(MemberNumbers.prefixOf(null)).isEqualTo("MEM");
        assertThat(MemberNumbers.prefixOf("x".repeat(80))).hasSize(8);
    }

    @Test
    void numbersArePaddedToFourDigitsAndGrowBeyond() {
        assertThat(MemberNumbers.format("LOTUS", 1)).isEqualTo("LOTUS-0001");
        assertThat(MemberNumbers.format("LOTUS", 42)).isEqualTo("LOTUS-0042");
        assertThat(MemberNumbers.format("LOTUS", 12345)).isEqualTo("LOTUS-12345");
        assertThat(MemberNumbers.format(MemberNumbers.prefixOf("x".repeat(80)), 9_999_999_999L).length()).as("always within the 30-character column").isLessThanOrEqualTo(30);
    }

    // ---- invites -------------------------------------------------------------------------------------------------

    private static MemberInvite invite(Instant expires, int max, int used, Instant revoked) {
        MemberInvite invite = new MemberInvite();
        invite.setExpiresAt(expires);
        invite.setMaxUses(max);
        invite.setUsedCount(used);
        invite.setRevokedAt(revoked);
        return invite;
    }

    @Test
    void inviteStateIsDerivedWithRevokedBeforeExpiredBeforeUsedUp() {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        Instant future = now.plusSeconds(60);
        Instant past = now.minusSeconds(60);

        assertThat(InviteService.stateOf(invite(future, 5, 4, null), now)).isEqualTo("ACTIVE");
        assertThat(InviteService.stateOf(invite(future, 5, 5, null), now)).isEqualTo("USED_UP");
        assertThat(InviteService.stateOf(invite(past, 5, 0, null), now)).isEqualTo("EXPIRED");
        assertThat(InviteService.stateOf(invite(now, 5, 0, null), now)).as("expires at this very instant").isEqualTo("EXPIRED");
        assertThat(InviteService.stateOf(invite(past, 5, 5, null), now)).isEqualTo("EXPIRED");
        assertThat(InviteService.stateOf(invite(future, 5, 5, past), now)).isEqualTo("REVOKED");
        assertThat(InviteService.stateOf(invite(past, 5, 5, past), now)).isEqualTo("REVOKED");
    }

    @Test
    void theQrCodeDecodesBackToTheLink() throws Exception {
        String link = "https://app.example.test/join/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde";

        byte[] png = QrCodes.png(link, InviteService.QR_SIZE);

        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(InviteService.QR_SIZE);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(link);
    }
}
