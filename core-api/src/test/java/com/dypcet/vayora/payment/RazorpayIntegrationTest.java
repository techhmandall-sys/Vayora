package com.dypcet.vayora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.booking.FlexTier;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.payment.PaymentChoice.Checkout;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.vehicle.VehicleService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature 46 with Razorpay's test keys, against a stand-in for Razorpay that answers here:
 * nothing reaches Razorpay. A ride's leg costs Rs 270 and the fee is 10%, so one seat is
 * Rs 297, 29700 paise.
 */
@AutoConfigureMockMvc
@Import(RazorpayIntegrationTest.StandIn.class)
class RazorpayIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "test-secret-for-the-stand-in";
    private static final RestClient.Builder BUILDER = RestClient.builder();
    private static final MockRestServiceServer RAZORPAY = MockRestServiceServer.bindTo(BUILDER)
            .ignoreExpectOrder(true).build();
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @TestConfiguration
    static class StandIn {
        @Bean
        @Primary
        RazorpayClient razorpayStandIn() {
            return new RazorpayClient(BUILDER, "https://api.razorpay.com", "rzp_test_vayora", SECRET);
        }
    }

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private BookingService bookings;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private long passenger;
    private long rideId;

    @BeforeEach
    void setUp() {
        RAZORPAY.reset();
        passenger = user(UserRole.PASSENGER);
        long driver = user(UserRole.DRIVER);
        long vehicle = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        Instant departure = LocalDate.now(ZONE).plusDays(5).atTime(7, 0).atZone(ZONE).toInstant();
        rideId = rides.publish(driver, new PublishRide(vehicle, departure, 3, true,
                List.of(new StopInput("Kolhapur", 16.7050, 74.2433, 0), new StopInput("Satara", 17.6805, 74.0183, 130)),
                List.of(new BigDecimal("270.00")), null, false, null, 0, false, 0)).id();
    }

    @Test
    void aCheckoutIsOpenedForWhatTheServerSaysIsDueAndTheTermsSaySo() throws Exception {
        RAZORPAY.expect(requestTo("https://api.razorpay.com/v1/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"amount\": 29700, \"currency\": \"INR\"}"))
                .andRespond(withSuccess("{\"id\": \"order_A\", \"status\": \"created\"}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/api/v1/bookings/checkout").header("Authorization", bearer(passenger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": " + rideId + ", \"fromSeq\": 0, \"toSeq\": 1, \"seats\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order_A"))
                .andExpect(jsonPath("$.keyId").value("rzp_test_vayora"))
                .andExpect(jsonPath("$.amount").value(29700))
                .andExpect(jsonPath("$.testMode").value(true));
        RAZORPAY.verify();

        mvc.perform(get("/api/v1/payment-terms").header("Authorization", bearer(passenger)))
                .andExpect(jsonPath("$.provider").value("RAZORPAY_TEST"))
                .andExpect(jsonPath("$.checkout").value(true))
                .andExpect(jsonPath("$.flexibleTiers").value(false));
    }

    @Test
    void aPaidBookingIsBookedRecordedWithHowItWasPaidAndRefundedThroughRazorpayOnCancelling() {
        paymentIs("pay_B", "order_B", 29700, "authorized", "upi");
        RAZORPAY.expect(requestTo("https://api.razorpay.com/v1/payments/pay_B/capture"))
                .andExpect(content().json("{\"amount\": 29700, \"currency\": \"INR\"}")).andRespond(withSuccess());

        Booking booked = book(new Checkout("order_B", "pay_B", sign("order_B", "pay_B")));

        assertThat(jdbc.queryForMap("SELECT provider, provider_ref, method, amount FROM payment_intents WHERE booking_id = ?",
                booked.id())).containsEntry("provider", "RAZORPAY_TEST").containsEntry("provider_ref", "pay_B")
                .containsEntry("method", "UPI").containsEntry("amount", new BigDecimal("297.00"));
        RAZORPAY.verify();

        // Five days out the whole fare comes back; the fee is kept.
        RAZORPAY.reset();
        RAZORPAY.expect(requestTo("https://api.razorpay.com/v1/payments/pay_B/refund"))
                .andExpect(content().json("{\"amount\": 27000}")).andRespond(withSuccess());
        bookings.cancel(passenger, booked.id());
        RAZORPAY.verify();
    }

    @Test
    void aForgedSignatureBooksNothingAndAsksRazorpayNothing() {
        RAZORPAY.expect(ExpectedCount.never(), requestTo("https://api.razorpay.com/v1/payments/pay_C"));

        assertDeclined(() -> book(new Checkout("order_C", "pay_C", sign("order_C", "pay_SOMETHING_ELSE"))));
        assertThat(bookedCount()).isZero();
        RAZORPAY.verify();
    }

    @Test
    void onePaymentPaysForOneBookingOnly() {
        paymentIs("pay_D", "order_D", 29700, "captured", "card");
        Checkout paid = new Checkout("order_D", "pay_D", sign("order_D", "pay_D"));
        book(paid);

        assertDeclined(() -> book(paid));
        assertThat(bookedCount()).isEqualTo(1);
        RAZORPAY.verify();   // asked about the payment once, by the first booking
    }

    @Test
    void anUnderpaymentIsRefundedInFullAndBooksNothing() {
        paymentIs("pay_E", "order_E", 10000, "authorized", "card");
        RAZORPAY.expect(requestTo("https://api.razorpay.com/v1/payments/pay_E/capture")).andRespond(withSuccess());
        RAZORPAY.expect(requestTo("https://api.razorpay.com/v1/payments/pay_E/refund"))
                .andExpect(content().json("{\"amount\": 10000}")).andRespond(withSuccess());

        assertThatThrownBy(() -> book(new Checkout("order_E", "pay_E", sign("order_E", "pay_E"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(bookedCount()).isZero();
        RAZORPAY.verify();   // the refund came after the booking's transaction rolled back
    }

    @Test
    void aFlexibleBookingIsRefusedWhileOnlyThePassengerCanPay() {
        assertThatThrownBy(() -> bookings.request(passenger, rideId, 0, 1, 1,
                PaymentChoice.of(null, null, new Checkout("order_F", "pay_F", sign("order_F", "pay_F"))), null,
                FlexTier.FLEXIBLE, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).startsWith("A booking paid at checkout is for this ride only");
                });
    }

    private void paymentIs(String id, String orderId, long paise, String status, String method) {
        RAZORPAY.expect(ExpectedCount.once(), requestTo("https://api.razorpay.com/v1/payments/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\": \"%s\", \"amount\": %d, \"currency\": \"INR\", \"status\": \"%s\", \"order_id\": \"%s\", \"method\": \"%s\"}"
                        .formatted(id, paise, status, orderId, method), MediaType.APPLICATION_JSON));
    }

    private Booking book(Checkout checkout) {
        return bookings.request(passenger, rideId, 0, 1, 1, PaymentChoice.of(null, null, checkout), null);
    }

    private int bookedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE passenger_id = ?", Integer.class, passenger);
    }

    private static void assertDeclined(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
    }

    /** What Razorpay would sign, made here independently of the code under test. */
    private static String sign(String orderId, String paymentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.issue(userId, UserRole.PASSENGER);
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Test " + role, role).id();
    }
}
