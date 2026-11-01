package com.dypcet.vayora.account;

import com.dypcet.vayora.account.AccountService.EmergencyContact;
import com.dypcet.vayora.account.MessageSender.Channel;
import com.dypcet.vayora.audit.AuditService;
import com.dypcet.vayora.notification.NotificationService.Type;
import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.notification.Text;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The emergency button.
 *
 * <p>Raising an alert records it, tells the administrators, and hands a message for each
 * of the member's emergency contacts to the message sender. With the mock sender nobody
 * outside the platform is told anything, and the answer says so: this is not a substitute
 * for calling the emergency services, and must never read as if it were. With a real
 * sender a text can fail; it never stops the alert, and the answer says whom it missed.
 */
@Service
public class SosService {

    /**
     * @param contactsTold   how many contacts a message was handed to the sender for
     * @param reallySent     false when the sender is the mock, i.e. nobody was messaged
     * @param contactsMissed contacts whose text the sender could not take; always 0 with the mock
     */
    public record Raised(long alertId, int contactsTold, boolean reallySent, String emergencyNumber,
            int contactsMissed) {
    }

    public record Alert(long id, long userId, String userName, String userPhone, Long rideId,
            Double lat, Double lon, int contactsTold, String sender, String status,
            Instant createdAt, int contactsMissed) {
    }

    private static final String EMERGENCY_NUMBER = "112";
    private static final Logger log = LoggerFactory.getLogger(SosService.class);

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final MessageSender sender;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;
    private final Duration repeatWindow;

    public SosService(JdbcTemplate jdbc, AccountService accounts, MessageSender sender,
            NotificationService notifications, AuditService audit, Clock clock,
            @Value("${vayora.sos.repeat-window}") Duration repeatWindow) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.sender = sender;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.repeatWindow = repeatWindow;
    }

    /**
     * Raises an alert. Pressing again within the repeat window returns the alert already
     * raised instead of messaging everyone a second time: a frightened person presses
     * more than once.
     *
     * @param rideId the ride it is happening on, if the member is on one; ignored if not theirs
     */
    @Transactional
    public Raised raise(long userId, Long rideId, Double lat, Double lon) {
        // One alert at a time per member.
        String name = jdbc.queryForObject(
                "SELECT full_name FROM users WHERE id = ? FOR UPDATE", String.class, userId);
        Instant now = clock.instant();
        List<Raised> recent = jdbc.query("""
                SELECT id, contacts_told, contacts_missed, sender FROM sos_alerts
                WHERE user_id = ? AND created_at > ? ORDER BY id DESC LIMIT 1""",
                (rs, n) -> raised(rs.getLong("id"), rs.getInt("contacts_told"), rs.getString("sender"),
                        rs.getInt("contacts_missed")),
                userId, utc(now.minus(repeatWindow)));
        if (!recent.isEmpty()) {
            return recent.get(0);
        }

        Long ride = rideId != null && isOnRide(userId, rideId) ? rideId : null;
        boolean located = lat != null && lon != null;
        List<EmergencyContact> contacts = accounts.contacts(userId);
        String where = located
                ? String.format(Locale.ROOT, " Last known position: %.5f, %.5f.", lat, lon)
                : " Their position is not known.";
        // Each contact on their own: a text that cannot be sent must never stop the alert.
        int told = 0;
        for (EmergencyContact contact : contacts) {
            try {
                sender.send(Channel.PHONE, contact.phone(), "VAYORA: " + name
                        + " pressed the emergency button." + where
                        + " Try to reach them, and call " + EMERGENCY_NUMBER + " if you cannot.");
                told++;
            } catch (RuntimeException e) {
                log.warn("Emergency text for member {} not sent: {}", userId, e.getMessage());
            }
        }
        int missed = contacts.size() - told;
        Long alertId = jdbc.queryForObject("""
                INSERT INTO sos_alerts (user_id, ride_id, location, contacts_told, contacts_missed, sender)
                VALUES (?, ?, CASE WHEN ? THEN ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography END,
                        ?, ?, ?)
                RETURNING id""",
                Long.class, userId, ride, located, located ? lon : 0.0, located ? lat : 0.0,
                told, missed, sender.name(Channel.PHONE));

        for (Long adminId : jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'", Long.class)) {
            notifications.send(adminId, Type.SOS_RAISED,
                    Text.of("{name} pressed the emergency button. Open the emergency queue.", "name", name), null);
        }
        return raised(alertId, told, sender.name(Channel.PHONE), missed);
    }

    /** Alerts no administrator has acknowledged yet, oldest first. */
    public List<Alert> open() {
        return jdbc.query("""
                SELECT a.id, a.user_id, u.full_name, u.phone, a.ride_id,
                       ST_Y(a.location::geometry) AS lat, ST_X(a.location::geometry) AS lon,
                       a.contacts_told, a.contacts_missed, a.sender, a.status, a.created_at
                FROM sos_alerts a JOIN users u ON u.id = a.user_id
                WHERE a.status = 'OPEN' ORDER BY a.id""",
                (rs, n) -> new Alert(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getString("full_name"), rs.getString("phone"),
                        (Long) rs.getObject("ride_id"), (Double) rs.getObject("lat"),
                        (Double) rs.getObject("lon"), rs.getInt("contacts_told"),
                        rs.getString("sender"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("contacts_missed")));
    }

    @Transactional
    public void acknowledge(long adminId, long alertId) {
        int changed = jdbc.update("""
                UPDATE sos_alerts SET status = 'ACKNOWLEDGED', acknowledged_by = ?,
                                      acknowledged_at = ?
                WHERE id = ? AND status = 'OPEN'""",
                adminId, utc(clock.instant()), alertId);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No open alert with that id");
        }
        audit.record(adminId, "SOS_ACKNOWLEDGED", "sos_alert", alertId, Map.of());
    }

    private boolean isOnRide(long userId, long rideId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM rides r
                WHERE r.id = ? AND (r.driver_id = ? OR EXISTS (
                    SELECT 1 FROM bookings b WHERE b.ride_id = r.id AND b.passenger_id = ?))""",
                Integer.class, rideId, userId, userId) > 0;
    }

    private static Raised raised(long alertId, int contactsTold, String senderName, int contactsMissed) {
        return new Raised(alertId, contactsTold, !MockMessageSender.NAME.equals(senderName),
                EMERGENCY_NUMBER, contactsMissed);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
