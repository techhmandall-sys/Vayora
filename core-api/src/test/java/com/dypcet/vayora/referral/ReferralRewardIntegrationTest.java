package com.dypcet.vayora.referral;

import static org.assertj.core.api.Assertions.assertThat;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.booking.TripService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.notification.NotificationService.Type;
import com.dypcet.vayora.pass.PassProperties.Kind;
import com.dypcet.vayora.pass.PassService;
import com.dypcet.vayora.pass.PassService.Pass;
import com.dypcet.vayora.payment.PaymentChoice;
import com.dypcet.vayora.receipt.ReceiptService;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.vehicle.VehicleService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Feature 72: once an invited member completes a first trip, each passenger of the pair gets a free pass. */
class ReferralRewardIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private IdentityService identity;

    @Autowired
    private ReferralService referrals;

    @Autowired
    private PassService passes;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private ReceiptService receipts;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private BookingService bookings;

    @Autowired
    private TripService trips;

    @Test
    void eachPassengerOfAQualifiedReferralGetsOneFreePassAndNoMore() throws IOException {
        long inviter = user(UserRole.PASSENGER);
        long invited = user(UserRole.PASSENGER);
        referrals.recordSignUp(inviter, invited);

        tripFor(invited);

        assertThat(passes.of(inviter)).singleElement().satisfies(pass -> isFreeWeekly(pass));
        assertThat(passes.of(invited)).singleElement().satisfies(pass -> isFreeWeekly(pass));
        assertThat(notifications.forUser(inviter)).filteredOn(n -> n.type() == Type.REFERRAL_REWARD)
                .singleElement().satisfies(n -> assertThat(n.message()).startsWith("Test PASSENGER joined with your invitation"));
        assertThat(notifications.forUser(invited)).filteredOn(n -> n.type() == Type.REFERRAL_REWARD).hasSize(1);

        // A second trip qualifies nobody again.
        tripFor(invited);
        assertThat(passes.of(inviter)).hasSize(1);
        assertThat(passes.of(invited)).hasSize(1);

        // The pass does what a bought one does: no service fee while it runs.
        assertThat(passes.covers(inviter, Instant.now())).isTrue();
        String receipt = text(receipts.forPass(inviter, passes.of(inviter).get(0).id()));
        assertThat(receipt).contains("as a reward for an invitation. Nothing was paid.")
                .doesNotContain("Payment provider");
    }

    @Test
    void aDriverOnEitherSideGetsNoPass() {
        long invitingDriver = user(UserRole.DRIVER);
        long invited = user(UserRole.PASSENGER);
        referrals.recordSignUp(invitingDriver, invited);

        tripFor(invited);

        assertThat(passes.of(invitingDriver)).isEmpty();
        assertThat(passes.of(invited)).singleElement().satisfies(pass -> isFreeWeekly(pass));
        assertThat(notifications.forUser(invitingDriver)).noneMatch(n -> n.type() == Type.REFERRAL_REWARD);
    }

    @Test
    void aRewardStartsWhenAPassAlreadyRunningEnds() {
        long inviter = user(UserRole.PASSENGER);
        long invited = user(UserRole.PASSENGER);
        referrals.recordSignUp(inviter, invited);
        Pass bought = passes.buy(inviter, Kind.MONTHLY, PaymentChoice.card());

        tripFor(invited);

        assertThat(passes.of(inviter)).filteredOn(Pass::reward).singleElement()
                .satisfies(reward -> assertThat(reward.startsAt()).isEqualTo(bought.endsAt()));
    }

    private static void isFreeWeekly(Pass pass) {
        assertThat(pass.reward()).isTrue();
        assertThat(pass.kind()).isEqualTo(Kind.WEEKLY);
        assertThat(pass.price()).isEqualByComparingTo("0");
        assertThat(Duration.between(pass.startsAt(), pass.endsAt())).isEqualTo(Duration.ofDays(7));
    }

    /** A completed trip for this passenger, on a ride of its own. */
    private void tripFor(long passenger) {
        long driver = user(UserRole.DRIVER);
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        long rideId = rides.publish(driver, new PublishRide(vehicleId, Instant.now().plus(Duration.ofMinutes(30)),
                3, true, List.of(new StopInput("Kolhapur", 16.7050, 74.2433, 0),
                        new StopInput("Pune", 18.5204, 73.8567, 270)),
                List.of(new BigDecimal("450.00")))).id();
        bookings.request(passenger, rideId, 0, 1, 1, PaymentChoice.card(), null);
        trips.start(driver, rideId);
        trips.complete(driver, rideId);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Test " + role, role).id();
    }
}
