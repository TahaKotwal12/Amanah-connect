package com.amanahconnect.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.billing.AbstractFinanceIT;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanLimitExceededException;
import com.amanahconnect.plan.StorageUsageProvider;
import com.amanahconnect.support.ApiClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FileServiceIT extends AbstractFinanceIT {

    @Autowired FileService files;
    @Autowired StorageUsageProvider usage;

    private FileService.Upload prepare(StoredFileKind kind, String type, long size) {
        return files.prepareUpload(communityA.getId(), kind, type, size);
    }

    /** What the browser does after the signed PUT: the object appears under the key. */
    private String uploaded(StoredFileKind kind, String type, long size) {
        String key = prepare(kind, type, size).key();
        storage.put(key, type, size);
        return key;
    }

    private void limitStorageMb(long mb) {
        Plan limited = data.customPlan("Tiny storage", Map.of("storage_mb", mb), Map.of("upi_qr", true));
        jdbc.update("update communities set plan_id = ? where id = ?", limited.getId(), communityA.getId());
    }

    private long storedBytes(UUID community) {
        return jdbc.queryForObject("select coalesce(sum(size_bytes), 0) from stored_files where community_id = ? and deleted_at is null", Long.class, community);
    }

    // ---- presigned upload: what is allowed ----------------------------------------------------------------------------------

    @Test
    void keysAreBuiltByTheServerUnderTheCommunityAndKind() {
        for (Object[] c : new Object[][] {{StoredFileKind.LOGO, "logo", "image/png", "png"}, {StoredFileKind.LEDGER_ATTACHMENT, "ledger", "application/pdf", "pdf"},
                {StoredFileKind.SUPPORT_ATTACHMENT, "support", "image/jpeg", "jpg"}, {StoredFileKind.LOGO, "logo", "image/webp", "webp"}}) {
            FileService.Upload upload = prepare((StoredFileKind) c[0], (String) c[2], 1234);

            assertThat(upload.key()).matches("^communities/" + communityA.getId() + "/" + c[1] + "/[0-9a-f-]{36}\\." + c[3] + "$");
            assertThat(upload.method()).isEqualTo("PUT");
            assertThat(upload.headers()).containsEntry("Content-Type", (String) c[2]).containsEntry("Content-Length", "1234");
            assertThat(storage.signedSizes.get(upload.key())).as("signed for exactly that size").isEqualTo(1234L);
            assertThat(upload.expiresAt()).isNotNull();
        }
        assertThat(prepare(StoredFileKind.LOGO, "image/png", 10).key()).as("every upload gets its own key").isNotEqualTo(prepare(StoredFileKind.LOGO, "image/png", 10).key());
    }

    @Test
    void onlyTheKindsOwnTypesAreAllowed() {
        for (String bad : new String[] {"image/svg+xml", "text/html", "application/octet-stream", "image/gif", "application/javascript", "application/x-msdownload", "", "png", "image/png; charset=x"}) {
            assertThatThrownBy(() -> prepare(StoredFileKind.LEDGER_ATTACHMENT, bad, 100)).as(bad).isInstanceOf(FileRejectedException.class).hasMessageStartingWith("contentType: must be ");
        }
        assertThatThrownBy(() -> prepare(StoredFileKind.LOGO, "application/pdf", 100)).as("a logo is not a PDF").isInstanceOf(FileRejectedException.class).hasMessage("contentType: must be image/png, image/jpeg or image/webp");
        assertThatThrownBy(() -> prepare(StoredFileKind.RECEIPT_PDF, "image/png", 100)).isInstanceOf(FileRejectedException.class);
        assertThat(prepare(StoredFileKind.LEDGER_ATTACHMENT, "IMAGE/PNG", 100).key()).as("case does not matter").endsWith(".png");
        assertThat(prepare(StoredFileKind.SUPPORT_ATTACHMENT, " application/pdf ", 100).key()).endsWith(".pdf");
    }

    @Test
    void sizesMustBePositiveAndWithinTheKindsLimit() {
        assertThat(prepare(StoredFileKind.LOGO, "image/png", 524288).maxBytes()).isEqualTo(524288);
        assertThatThrownBy(() -> prepare(StoredFileKind.LOGO, "image/png", 524289)).isInstanceOf(FileRejectedException.class).hasMessageContaining("sizeBytes: must be between 1 and 524288");
        assertThatThrownBy(() -> prepare(StoredFileKind.LOGO, "image/png", 0)).isInstanceOf(FileRejectedException.class);
        assertThatThrownBy(() -> prepare(StoredFileKind.LOGO, "image/png", -5)).isInstanceOf(FileRejectedException.class);
        assertThatThrownBy(() -> files.prepareUpload(communityA.getId(), StoredFileKind.LOGO, "image/png", null)).isInstanceOf(FileRejectedException.class);
        assertThat(prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 5 * 1024 * 1024).maxBytes()).isEqualTo(5 * 1024 * 1024);
        assertThatThrownBy(() -> prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 5 * 1024 * 1024 + 1)).isInstanceOf(FileRejectedException.class);
    }

    @Test
    void anUploadThatWouldNotFitInThePlanIsRefusedBeforeTheBrowserSendsAnything() {
        limitStorageMb(1);
        files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, uploaded(StoredFileKind.LEDGER_ATTACHMENT, "application/pdf", 900_000));

        assertThat(prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 100_000).key()).isNotBlank();
        assertThatThrownBy(() -> prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 200_000)).isInstanceOfSatisfying(PlanLimitExceededException.class, e -> {
            assertThat(e.getMessage()).contains("1 MB of storage");
            assertThat(e.properties()).containsEntry("limit", "storage_mb");
        });
    }

    @Test
    void theApiAnswers402WhenThePlanIsFullAnd400ForABadRequest() {
        limitStorageMb(1);
        files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, uploaded(StoredFileKind.LEDGER_ATTACHMENT, "application/pdf", 1_000_000));

        ApiClient.Response full = asA("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", "image/png", "sizeBytes", 100_000));
        ApiClient.Response bad = asA("POST", LEDGER + "/attachments/upload-url", Map.of("contentType", "text/html", "sizeBytes", 100));

        assertThat(full.status()).isEqualTo(402);
        assertThat(full.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(bad.status()).isEqualTo(400);
    }

    // ---- accepting what was uploaded ---------------------------------------------------------------------------------------

    @Test
    void anUploadedFileIsRecordedWithItsRealTypeAndSize() {
        String key = uploaded(StoredFileKind.SUPPORT_ATTACHMENT, "image/png", 4321);

        StoredFile file = files.accept(communityA.getId(), StoredFileKind.SUPPORT_ATTACHMENT, key);

        assertThat(file.getObjectKey()).isEqualTo(key);
        assertThat(file.getContentType()).isEqualTo("image/png");
        assertThat(file.getSizeBytes()).isEqualTo(4321);
        assertThat(file.getKind()).isEqualTo(StoredFileKind.SUPPORT_ATTACHMENT);
        assertThat(file.getCommunityId()).isEqualTo(communityA.getId());
        assertThat(files.accept(communityA.getId(), StoredFileKind.SUPPORT_ATTACHMENT, key).getId()).as("accepting twice is the same record").isEqualTo(file.getId());
        assertThat(count("select count(*) from stored_files where object_key = ?", key)).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotWhatItClaimsIsRefusedAndRemoved() {
        String key = prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 100).key();
        storage.putRaw(key, "image/png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, key)).isInstanceOf(FileRejectedException.class).hasMessageContaining("content is not really image/png");

        assertThat(storage.has(key)).as("thrown away").isFalse();
        assertThat(count("select count(*) from stored_files where object_key = ?", key)).isZero();
    }

    @Test
    void aPdfPassedOffAsAnImageIsRefused() {
        String key = prepare(StoredFileKind.LOGO, "image/png", 100).key();
        storage.putRaw(key, "image/png", com.amanahconnect.file.MagicBytes.sample("application/pdf"));

        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LOGO, key)).isInstanceOf(FileRejectedException.class);
        assertThat(storage.has(key)).isFalse();
    }

    @Test
    void theStoredTypeMustMatchTheExtensionAndTheAllowList() {
        UUID id = UUID.randomUUID();
        String prefix = "communities/" + communityA.getId() + "/ledger/";

        String wrongExtension = prefix + id + ".png";
        storage.put(wrongExtension, "image/jpeg", 100);
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, wrongExtension)).hasMessageContaining("type does not match its name");
        assertThat(storage.has(wrongExtension)).isFalse();

        String html = prefix + UUID.randomUUID() + ".png";
        storage.put(html, "text/html", 100);
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, html)).hasMessageContaining("not acceptable");
        assertThat(storage.has(html)).isFalse();

        String tooBig = prefix + UUID.randomUUID() + ".pdf";
        storage.put(tooBig, "application/pdf", 5L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, tooBig)).hasMessageContaining("at most 5242880 bytes");
        assertThat(storage.has(tooBig)).isFalse();

        String empty = prefix + UUID.randomUUID() + ".png";
        storage.put(empty, "image/png", 0);
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, empty)).isInstanceOf(FileRejectedException.class);
    }

    @Test
    void aKeyThatWasNotIssuedForThisCommunityAndKindIsRefusedAndLeftAlone() {
        String ofB = "communities/" + communityB.getId() + "/ledger/" + UUID.randomUUID() + ".png";
        storage.put(ofB, "image/png", 100);
        String logoKey = uploaded(StoredFileKind.LOGO, "image/png", 100);
        String neverUploaded = prepare(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 100).key();

        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, ofB)).hasMessage("not an attachment uploaded for this community");
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, logoKey)).as("a logo key is not an attachment key").hasMessageContaining("uploaded for this community");
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, neverUploaded)).hasMessage("the file has not been uploaded");
        for (String hostile : new String[] {"../../etc/passwd", "communities/" + communityA.getId() + "/ledger/../logo/" + UUID.randomUUID() + ".png", "communities/" + communityA.getId() + "/ledger/x.png",
                "http://evil.test/a.png", "", "  ", "communities/" + communityA.getId() + "/ledger/" + UUID.randomUUID() + ".svg", "communities/" + communityA.getId() + "/ledger/" + UUID.randomUUID() + ".png/../../x"}) {
            assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, hostile)).as(hostile).isInstanceOf(FileRejectedException.class);
        }
        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, null)).isInstanceOf(FileRejectedException.class);
        assertThat(storage.has(ofB)).as("another community's file is not touched").isTrue();
        assertThat(storage.has(logoKey)).isTrue();
    }

    // ---- storage usage against the plan -----------------------------------------------------------------------------------

    @Test
    void usageIsTheSumOfTheCommunitysLiveFiles() {
        assertThat(usage.usedBytes(communityA.getId())).isZero();
        String a = uploaded(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 1000);
        String b = uploaded(StoredFileKind.SUPPORT_ATTACHMENT, "application/pdf", 2500);
        files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, a);
        files.accept(communityA.getId(), StoredFileKind.SUPPORT_ATTACHMENT, b);

        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(3500);
        assertThat(usage.usedBytes(communityB.getId())).as("another community's files do not count").isZero();

        files.delete(communityA.getId(), a);

        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(2500);
        assertThat(storage.has(a)).as("the object is deleted too").isFalse();
        assertThat(jdbc.queryForObject("select deleted_at is not null from stored_files where object_key = ?", Boolean.class, a)).as("the row is kept as history").isTrue();
        files.delete(communityA.getId(), a);
        files.delete(communityA.getId(), "communities/" + communityA.getId() + "/ledger/" + UUID.randomUUID() + ".png");
        assertThat(usage.usedBytes(communityA.getId())).as("deleting twice, or something unknown, is harmless").isEqualTo(2500);
    }

    @Test
    void aFileThatWouldPushTheCommunityOverItsStorageIsRefusedAndDeleted() {
        limitStorageMb(1);
        files.accept(communityA.getId(), StoredFileKind.LEDGER_ATTACHMENT, uploaded(StoredFileKind.LEDGER_ATTACHMENT, "application/pdf", 700_000));
        String second = prepare(StoredFileKind.SUPPORT_ATTACHMENT, "application/pdf", 100_000).key(); // fits when asked...
        storage.put(second, "application/pdf", 600_000); // ...but the browser sent more than it announced (the object's real size is what counts)

        assertThatThrownBy(() -> files.accept(communityA.getId(), StoredFileKind.SUPPORT_ATTACHMENT, second)).isInstanceOf(PlanLimitExceededException.class);

        assertThat(storage.has(second)).isFalse();
        assertThat(storedBytes(communityA.getId())).isEqualTo(700_000);
    }

    @Test
    void twoUploadsRacingForTheLastSpaceCannotBothGetIn() throws Exception {
        limitStorageMb(1);
        String one = uploaded(StoredFileKind.LEDGER_ATTACHMENT, "application/pdf", 600_000);
        String two = uploaded(StoredFileKind.SUPPORT_ATTACHMENT, "application/pdf", 600_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> accepted = new ArrayList<>();
        try {
            List<Callable<Boolean>> jobs = List.of(
                    () -> tryAccept(StoredFileKind.LEDGER_ATTACHMENT, one),
                    () -> tryAccept(StoredFileKind.SUPPORT_ATTACHMENT, two));
            for (Future<Boolean> f : pool.invokeAll(jobs)) accepted.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(storedBytes(communityA.getId())).isLessThanOrEqualTo(1024 * 1024);
    }

    private boolean tryAccept(StoredFileKind kind, String key) {
        try {
            files.accept(communityA.getId(), kind, key);
            return true;
        } catch (PlanLimitExceededException e) {
            return false;
        }
    }

    @Test
    void generatedFilesAreRecordedAndReplacedNotDoubleCounted() {
        String key = "communities/" + communityA.getId() + "/receipts/" + UUID.randomUUID() + ".pdf";

        files.recordGenerated(communityA.getId(), StoredFileKind.RECEIPT_PDF, key, "application/pdf", 3000);
        files.recordGenerated(communityA.getId(), StoredFileKind.RECEIPT_PDF, key, "application/pdf", 3500);

        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(3500);
        assertThat(count("select count(*) from stored_files where object_key = ?", key)).isEqualTo(1);
    }

    @Test
    void aReceiptPdfCountsAsStorageOnceItIsMade() {
        UUID member = memberA("Receipt payer");
        UUID invoice = id(invoiceA(member, "300.00", TODAY.plusDays(3)));

        payOk(sessionA, invoice, "300.00");

        assertThat(count("select count(*) from stored_files where community_id = ? and kind = 'RECEIPT_PDF'", communityA.getId())).isEqualTo(1);
        assertThat(usage.usedBytes(communityA.getId())).isPositive();
    }

    // ---- through the real endpoints ---------------------------------------------------------------------------------------

    @Test
    void aLedgerAttachmentIsCountedWhenAnEntryUsesIt() {
        String key = uploaded(StoredFileKind.LEDGER_ATTACHMENT, "image/png", 8000);
        UUID category = categoryId("Maintenance", "EXPENSE");

        ApiClient.Response entry = asA("POST", LEDGER + "/entries", Map.of("type", "EXPENSE", "categoryId", category.toString(), "amount", "50.00", "entryDate", TODAY.toString(), "title", "Bill", "attachmentKey", key));

        assertThat(entry.status()).as(entry.body()).isEqualTo(201);
        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(8000);
    }

    @Test
    void aLedgerAttachmentWithTheWrongContentIsRefusedAtTheEndpoint() {
        String key = prepare(StoredFileKind.LEDGER_ATTACHMENT, "application/pdf", 200).key();
        storage.putRaw(key, "application/pdf", "MZ this is an executable".getBytes(StandardCharsets.UTF_8));
        UUID category = categoryId("Maintenance", "EXPENSE");

        ApiClient.Response entry = asA("POST", LEDGER + "/entries", Map.of("type", "EXPENSE", "categoryId", category.toString(), "amount", "50.00", "entryDate", TODAY.toString(), "title", "Bill", "attachmentKey", key));

        assertThat(entry.status()).isEqualTo(400);
        assertThat(entry.body()).contains("attachmentKey");
        assertThat(storage.has(key)).isFalse();
    }

    @Test
    void replacingAndRemovingALogoKeepsTheUsageRight() {
        String first = uploaded(StoredFileKind.LOGO, "image/png", 10_000);
        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("logoKey", first)).status()).isEqualTo(200);
        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(10_000);

        String second = uploaded(StoredFileKind.LOGO, "image/jpeg", 4_000);
        assertThat(asA("PATCH", "/api/v1/community/settings", Map.of("logoKey", second)).status()).isEqualTo(200);
        assertThat(usage.usedBytes(communityA.getId())).as("the replaced logo no longer counts").isEqualTo(4_000);
        assertThat(storage.has(first)).isFalse();

        assertThat(asA("DELETE", "/api/v1/community/settings/logo", null).status()).isEqualTo(200);
        assertThat(usage.usedBytes(communityA.getId())).isZero();
        assertThat(storage.has(second)).isFalse();
    }

    @Test
    void aSupportAttachmentIsCountedForTheCommunity() {
        String key = uploaded(StoredFileKind.SUPPORT_ATTACHMENT, "application/pdf", 7000);

        ApiClient.Response thread = asA("POST", "/api/v1/community/support/threads", Map.of("subject", "With file", "body", "see attached", "attachmentKey", key));

        assertThat(thread.status()).as(thread.body()).isEqualTo(201);
        assertThat(usage.usedBytes(communityA.getId())).isEqualTo(7000);
    }

    @Test
    void aDownloadLinkIsASignedShortLivedUrl() {
        String url = files.downloadUrl("communities/" + communityA.getId() + "/ledger/" + UUID.randomUUID() + ".png");

        assertThat(url).startsWith("https://").contains("sig=");
    }

    private UUID categoryId(String name, String type) {
        for (var c : asA("GET", LEDGER + "/categories", null).json()) {
            if (c.get("name").asString().equals(name) && c.get("type").asString().equals(type)) return id(c);
        }
        throw new AssertionError(name);
    }
}
