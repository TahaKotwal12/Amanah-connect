package com.amanahconnect.billing;

import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.member.Member;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The receipt PDF: community logo, name and address, receipt number, who paid, amount in figures and words, method and
 * date, a signature line, and a clear REVERSED banner when the payment was reversed. It is rendered from the database, so
 * a regenerated copy is identical to the first apart from that banner. The copy kept in object storage is a convenience;
 * if storage is down or empty the PDF is simply rendered again.
 *
 * <p>Text is set in Helvetica (Latin characters). Names in other scripts show as "?" until a Unicode font is bundled.
 */
@Service
public class ReceiptPdfService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPdfService.class);
    private static final Color TEAL = new Color(0x07, 0x36, 0x3E);
    private static final Color GOLD = new Color(0x8A, 0x6A, 0x12);
    private static final Color RED = new Color(0xB4, 0x23, 0x18);
    private static final Color MUTED = new Color(0x5B, 0x6F, 0x72);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final ReceiptRepository receipts;
    private final PaymentRecordRepository payments;
    private final CommunityRepository communities;
    private final ObjectStorage storage;
    private final com.amanahconnect.file.FileService files;

    public ReceiptPdfService(ReceiptRepository receipts, PaymentRecordRepository payments, CommunityRepository communities, ObjectStorage storage, com.amanahconnect.file.FileService files) {
        this.receipts = receipts;
        this.payments = payments;
        this.communities = communities;
        this.storage = storage;
        this.files = files;
    }

    public static String storageKey(UUID communityId, UUID receiptId) {
        return "communities/" + communityId + "/receipts/" + receiptId + ".pdf";
    }

    /** The PDF to show: a reversed receipt is always rendered fresh (so it carries the banner), others come from storage when stored. */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID communityId, UUID receiptId) {
        Receipt receipt = receipts.findByIdAndCommunityId(receiptId, communityId).orElseThrow(NotFoundException::new);
        boolean reversed = payments.existsByCommunityIdAndReversedOfId(communityId, receipt.getPaymentRecord().getId());
        if (!reversed && receipt.getPdfKey() != null) {
            try {
                Optional<byte[]> stored = storage.get(receipt.getPdfKey());
                if (stored.isPresent()) {
                    return stored.get();
                }
            } catch (RuntimeException e) {
                log.warn("Stored receipt PDF could not be read ({}); rendering it again", e.toString());
            }
        }
        return render(communityId, receipt);
    }

    /** Renders the receipt, stores the copy and records its key. Own transaction: it runs after the payment has committed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateAndStore(UUID communityId, UUID receiptId) {
        Receipt receipt = receipts.findByIdAndCommunityId(receiptId, communityId).orElseThrow(NotFoundException::new);
        byte[] bytes = render(communityId, receipt);
        String key = storageKey(communityId, receiptId);
        storage.put(key, bytes, "application/pdf");
        files.recordGenerated(communityId, com.amanahconnect.file.StoredFileKind.RECEIPT_PDF, key, "application/pdf", bytes.length);
        receipt.setPdfKey(key);
        receipts.save(receipt);
    }

    byte[] render(UUID communityId, Receipt receipt) {
        PaymentRecord payment = receipt.getPaymentRecord();
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        Optional<PaymentRecord> reversal = payments.findByCommunityIdAndReversedOfId(communityId, payment.getId());
        String currency = community.getCurrency();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(document, out);
            document.addTitle("Receipt " + receipt.getReceiptNo());
            document.addCreator("Amanah Connect");
            document.open();
            header(document, community);
            document.add(new Paragraph(" "));
            Paragraph title = new Paragraph("PAYMENT RECEIPT", font(18, Font.BOLD, TEAL));
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            if (reversal.isPresent()) {
                Paragraph banner = new Paragraph("REVERSED on " + DATE.format(reversal.get().getReceivedOn()) + ": " + clean(reversal.get().getReversalReason()), font(11, Font.BOLD, RED));
                banner.setAlignment(Element.ALIGN_CENTER);
                banner.setSpacingBefore(6);
                document.add(banner);
                Paragraph note = new Paragraph("This receipt is no longer valid as proof of payment.", font(9, Font.NORMAL, RED));
                note.setAlignment(Element.ALIGN_CENTER);
                document.add(note);
            }
            document.add(new Paragraph(" "));
            details(document, receipt, payment);
            document.add(new Paragraph(" "));
            amountBox(document, payment, currency);
            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));
            signature(document, community);
            Paragraph footer = new Paragraph("Receipt " + receipt.getReceiptNo() + ". This is a computer-generated receipt.", font(8, Font.NORMAL, MUTED));
            footer.setSpacingBefore(24);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the receipt PDF", e);
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------------------

    private void header(Document document, Community community) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {1, 4});
        table.setWidthPercentage(100);
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        Image logo = logo(community);
        if (logo != null) {
            logo.scaleToFit(64, 64);
            logoCell.addElement(logo);
        }
        table.addCell(logoCell);
        PdfPCell nameCell = new PdfPCell();
        nameCell.setBorder(Rectangle.NO_BORDER);
        nameCell.addElement(new Paragraph(clean(community.getName()), font(15, Font.BOLD, TEAL)));
        String address = String.join(", ", java.util.stream.Stream.of(community.getAddressLine1(), community.getAddressLine2(), community.getCity(), community.getState(), community.getPostalCode())
                .filter(s -> s != null && !s.isBlank()).toList());
        if (!address.isEmpty()) nameCell.addElement(new Paragraph(clean(address), font(9, Font.NORMAL, MUTED)));
        String contact = String.join("  |  ", java.util.stream.Stream.of(community.getContactPhone(), community.getContactEmail()).filter(s -> s != null && !s.isBlank()).toList());
        if (!contact.isEmpty()) nameCell.addElement(new Paragraph(clean(contact), font(9, Font.NORMAL, MUTED)));
        table.addCell(nameCell);
        document.add(table);
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell line = new PdfPCell(new Phrase(" ", font(2, Font.NORMAL, TEAL)));
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderColor(GOLD);
        line.setBorderWidth(1.5f);
        rule.addCell(line);
        document.add(rule);
    }

    private void details(Document document, Receipt receipt, PaymentRecord payment) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {2, 5});
        table.setWidthPercentage(100);
        Member member = payment.getMember();
        row(table, "Receipt no.", receipt.getReceiptNo());
        row(table, "Date received", DATE.format(payment.getReceivedOn()));
        row(table, "Received from", member != null ? member.getFullName() : payment.getDonorName());
        if (member != null) row(table, "Member no.", member.getMemberNo());
        if (payment.getInvoice() != null) {
            Invoice invoice = payment.getInvoice();
            String what = invoice.getDescription() != null ? invoice.getDescription() : (invoice.getFeePlan() == null ? "" : invoice.getFeePlan().getName());
            row(table, "Against invoice", invoice.getInvoiceNo() + (invoice.getPeriod() == null ? "" : "  (" + invoice.getPeriod() + ")"));
            if (!what.isBlank()) row(table, "For", what);
        } else {
            row(table, "For", "Donation");
        }
        row(table, "Payment method", payment.getMethod().name());
        if (payment.getReference() != null) row(table, "Reference", payment.getReference());
        document.add(table);
    }

    private void amountBox(Document document, PaymentRecord payment, String currency) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setPadding(10);
        cell.setBorderColor(TEAL);
        cell.setBorderWidth(1f);
        cell.addElement(new Paragraph("Amount received:  " + currency + " " + Formats.amount(payment.getAmount(), currency), font(14, Font.BOLD, TEAL)));
        Paragraph words = new Paragraph(AmountInWords.of(Money.of(payment.getAmount()).amount(), currency), font(10, Font.ITALIC, MUTED));
        words.setSpacingBefore(4);
        cell.addElement(words);
        table.addCell(cell);
        document.add(table);
    }

    private void signature(Document document, Community community) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {3, 2});
        table.setWidthPercentage(100);
        PdfPCell empty = new PdfPCell(new Phrase(" "));
        empty.setBorder(Rectangle.NO_BORDER);
        table.addCell(empty);
        PdfPCell sign = new PdfPCell();
        sign.setBorder(Rectangle.TOP);
        sign.setBorderColor(MUTED);
        sign.setPaddingTop(4);
        Paragraph p = new Paragraph("Authorised signatory", font(9, Font.NORMAL, MUTED));
        p.setAlignment(Element.ALIGN_CENTER);
        sign.addElement(p);
        Paragraph c = new Paragraph(clean(community.getName()), font(9, Font.BOLD, TEAL));
        c.setAlignment(Element.ALIGN_CENTER);
        sign.addElement(c);
        table.addCell(sign);
        document.add(table);
    }

    private static void row(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, font(10, Font.NORMAL, MUTED)));
        l.setBorder(Rectangle.BOTTOM);
        l.setBorderColor(new Color(0xE3, 0xEA, 0xEB));
        l.setPadding(5);
        PdfPCell v = new PdfPCell(new Phrase(clean(value), font(10, Font.BOLD, Color.BLACK)));
        v.setBorder(Rectangle.BOTTOM);
        v.setBorderColor(new Color(0xE3, 0xEA, 0xEB));
        v.setPadding(5);
        table.addCell(l);
        table.addCell(v);
    }

    private Image logo(Community community) {
        if (community.getLogoKey() == null) {
            return null;
        }
        try {
            Optional<byte[]> bytes = storage.get(community.getLogoKey());
            return bytes.isPresent() ? Image.getInstance(bytes.get()) : null;
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Logo not usable on the receipt: {}", e.toString());
            return null;
        }
    }

    private static Font font(float size, int style, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }

    /** Helvetica covers Latin-1; anything else would vanish or throw, so it becomes "?". */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> out.append(cp >= 0x20 && cp <= 0xFF && cp != 0x7F ? new String(Character.toChars(cp)) : cp == '\n' ? " " : "?"));
        return out.toString();
    }
}
