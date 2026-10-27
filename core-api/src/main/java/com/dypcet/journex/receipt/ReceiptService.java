package com.dypcet.journex.receipt;

import com.dypcet.journex.payment.MockPaymentProvider;
import com.dypcet.journex.payment.RazorpayPaymentProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Receipts for a booking's payment or a pass: as PDF, and as the same facts for the app to
 * show in the member's own language (feature 50).
 *
 * <p>A receipt states what the records say, and nothing else. While the provider is the
 * mock, every receipt says plainly that no money moved. None is a tax invoice: the
 * platform does not handle GST.
 *
 * <p>The built-in PDF fonts cover Latin script only, so the PDF is in English and any
 * other character in a name or stop is shown as "?". The app's receipt has no such limit.
 */
@Service
public class ReceiptService {

    private static final ZoneId CORRIDOR_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).withZone(CORRIDOR_ZONE);

    /**
     * What a booking's receipt says, as the records say it.
     *
     * @param refunded     0 when nothing came back
     * @param refundReason null when nothing came back
     * @param provider     MOCK means no money moved
     */
    public record BookingReceipt(long bookingId, String passenger, String driver, String fromStop, String toStop,
            int seats, Instant boarding, String status, Instant paidAt, String method, BigDecimal fare,
            BigDecimal serviceFee, BigDecimal total, BigDecimal refunded, String refundReason, String provider) {
    }

    /**
     * What a pass's receipt says.
     *
     * @param paidAt when it was bought, or given if a reward
     * @param method null for a reward: nothing was paid
     * @param reward given free for an invitation
     */
    public record PassReceipt(long passId, String passenger, String kind, BigDecimal price, Instant startsAt,
            Instant endsAt, Instant paidAt, String method, String provider, boolean reward) {
    }

    private final JdbcTemplate jdbc;

    public ReceiptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The receipt for the payment on one of the passenger's bookings, as PDF. */
    public byte[] forBooking(long passengerId, long bookingId) {
        return pdf(lines(booking(passengerId, bookingId)));
    }

    /** The receipt for one of the passenger's passes, as PDF. */
    public byte[] forPass(long passengerId, long passId) {
        return pdf(lines(pass(passengerId, passId)));
    }

    /** What the receipt for the payment on one of the passenger's bookings says. */
    public BookingReceipt booking(long passengerId, long bookingId) {
        List<BookingReceipt> found = jdbc.query("""
                SELECT b.id, u.full_name AS passenger, d.full_name AS driver,
                       s.name AS from_stop, t.name AS to_stop, b.seats, b.status,
                       r.departure_time + make_interval(mins => s.offset_minutes
                                                         + COALESCE(b.pickup_offset_minutes, 0)) AS boarding,
                       p.fare, p.service_fee, p.amount, p.method, p.provider, p.created_at,
                       COALESCE(f.amount, 0) AS refunded, f.reason AS refund_reason
                FROM bookings b
                JOIN payment_intents p ON p.booking_id = b.id
                JOIN users u ON u.id = b.passenger_id
                JOIN rides r ON r.id = b.ride_id
                JOIN users d ON d.id = r.driver_id
                JOIN ride_stops s ON s.ride_id = b.ride_id AND s.seq = b.from_seq
                JOIN ride_stops t ON t.ride_id = b.ride_id AND t.seq = b.to_seq
                LEFT JOIN refunds f ON f.booking_id = b.id
                WHERE b.id = ? AND b.passenger_id = ?""",
                (rs, n) -> new BookingReceipt(rs.getLong("id"), rs.getString("passenger"), rs.getString("driver"),
                        rs.getString("from_stop"), rs.getString("to_stop"), rs.getInt("seats"),
                        rs.getObject("boarding", OffsetDateTime.class).toInstant(), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("method"),
                        rs.getBigDecimal("fare"), rs.getBigDecimal("service_fee"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("refunded"), rs.getString("refund_reason"), rs.getString("provider")),
                bookingId, passengerId);
        // A booking with no payment, or someone else's, looks the same as none at all.
        return found.stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No receipt for that booking"));
    }

    /** What the receipt for one of the passenger's passes says. */
    public PassReceipt pass(long passengerId, long passId) {
        List<PassReceipt> found = jdbc.query("""
                SELECT p.id, u.full_name AS passenger, p.kind, p.price, p.starts_at, p.ends_at,
                       p.method, p.provider, p.created_at, p.reward_for_referee IS NOT NULL AS reward
                FROM passes p JOIN users u ON u.id = p.passenger_id
                WHERE p.id = ? AND p.passenger_id = ?""",
                (rs, n) -> new PassReceipt(rs.getLong("id"), rs.getString("passenger"), rs.getString("kind"),
                        rs.getBigDecimal("price"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("method"),
                        rs.getString("provider"), rs.getBoolean("reward")),
                passId, passengerId);
        return found.stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No receipt for that pass"));
    }

    private static List<String> lines(BookingReceipt receipt) {
        List<String> lines = new ArrayList<>(List.of(
                "Receipt for booking " + receipt.bookingId(),
                "",
                "Passenger: " + receipt.passenger(),
                "Driver: " + receipt.driver(),
                "Trip: " + receipt.fromStop() + " to " + receipt.toStop()
                        + ", " + receipt.seats() + (receipt.seats() == 1 ? " seat" : " seats"),
                "Boarding: " + when(receipt.boarding()),
                "Booking status: " + receipt.status(),
                "",
                "Paid: " + when(receipt.paidAt()) + " by " + receipt.method(),
                "Fare: " + rupees(receipt.fare()),
                "Service fee: " + rupees(receipt.serviceFee()),
                "Total: " + rupees(receipt.total())));
        if (receipt.refunded().signum() > 0) {
            lines.add("Refunded: " + rupees(receipt.refunded())
                    + " (" + receipt.refundReason().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
        }
        lines.addAll(footer(receipt.provider()));
        return lines;
    }

    private static List<String> lines(PassReceipt receipt) {
        List<String> lines = new ArrayList<>(List.of(
                "Receipt for travel pass " + receipt.passId(),
                "",
                "Passenger: " + receipt.passenger(),
                "Pass: " + receipt.kind().toLowerCase(Locale.ROOT),
                "Runs from " + when(receipt.startsAt()) + " to " + when(receipt.endsAt()),
                "No service fee is charged on bookings requested while it runs.",
                ""));
        if (receipt.reward()) {
            // Nothing was paid, so there is no provider to name.
            lines.addAll(List.of(
                    "Given free on " + when(receipt.paidAt()) + " as a reward for an invitation. Nothing was paid.",
                    "Total: " + rupees(receipt.price()),
                    "",
                    "This is not a tax invoice.",
                    "VAYORA, Kolhapur to Pune"));
            return lines;
        }
        lines.addAll(List.of(
                "Paid: " + when(receipt.paidAt()) + " by " + receipt.method(),
                "Total: " + rupees(receipt.price())));
        lines.addAll(footer(receipt.provider()));
        return lines;
    }

    private static List<String> footer(String provider) {
        List<String> lines = new ArrayList<>(List.of("", "Payment provider: " + provider));
        if (MockPaymentProvider.NAME.equals(provider)) {
            lines.add("SIMULATED PAYMENT: no money was taken or paid. This is a student project.");
        } else if (RazorpayPaymentProvider.TEST_NAME.equals(provider)) {
            lines.add("TEST MODE: paid in Razorpay's test mode, where no real money moves. This is a student project.");
        }
        lines.add("This is not a tax invoice.");
        lines.add("VAYORA, Kolhapur to Pune");
        return lines;
    }

    private static byte[] pdf(List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream text = new PDPageContentStream(document, page)) {
                text.beginText();
                text.newLineAtOffset(56, PDRectangle.A4.getHeight() - 72);
                text.setLeading(18);
                for (int i = 0; i < lines.size(); i++) {
                    text.setFont(i == 0 ? bold : regular, i == 0 ? 16 : 11);
                    text.showText(latin(lines.get(i)));
                    text.newLine();
                }
                text.endText();
            }
            document.getDocumentInformation().setTitle(latin(lines.get(0)));
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the built-in fonts can draw: printable ASCII. Anything else becomes "?". */
    static String latin(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> out.append(c >= 0x20 && c < 0x7F ? (char) c : '?'));
        return out.toString();
    }

    private static String rupees(BigDecimal amount) {
        return "Rs " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String when(Instant time) {
        return WHEN.format(time);
    }
}
