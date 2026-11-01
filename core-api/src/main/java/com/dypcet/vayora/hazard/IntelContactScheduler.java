package com.dypcet.vayora.hazard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks intel's contact scheduler (feature 80, CP-SAT) which bookings to ask to reconfirm
 * within a budget. Asking is all it ever recommends. Any failure is an empty answer: then
 * nobody is asked, and the bookings stay due.
 */
@Component
public class IntelContactScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntelContactScheduler.class);

    /**
     * @param departureDay the day the ride leaves, as a day count; limits per day group by it
     */
    public record Candidate(@JsonProperty("booking_id") long bookingId, @JsonProperty("ride_id") long rideId,
            @JsonProperty("departure_day") long departureDay, int seats,
            @JsonProperty("survival_probability") double survivalProbability,
            @JsonProperty("hours_to_departure") double hoursToDeparture) {
    }

    public record Limits(int contacts, @JsonProperty("contacts_per_ride") int contactsPerRide,
            @JsonProperty("seats_per_departure_day") int seatsPerDepartureDay,
            @JsonProperty("refill_horizon_hours") double refillHorizonHours,
            @JsonProperty("time_limit_seconds") double timeLimitSeconds) {
    }

    record Request(List<Candidate> candidates, Limits limits) {
    }

    record Response(@JsonProperty("booking_ids") List<Long> bookingIds, String status) {
    }

    private final RestClient client;

    @Autowired
    public IntelContactScheduler(RestClient.Builder builder, @Value("${vayora.intel.url}") String baseUrl,
            @Value("${vayora.intel.timeout}") Duration timeout,
            @Value("${vayora.reminders.targeting.solver-time-limit-seconds}") double solverSeconds) {
        // The solver may take its whole time limit before answering.
        this(builder.baseUrl(baseUrl).requestFactory(timeouts(timeout.plusMillis((long) (solverSeconds * 1000)))).build());
    }

    IntelContactScheduler(RestClient client) {
        this.client = client;
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    /** The bookings to ask, or empty if intel could not say. */
    public Optional<List<Long>> plan(List<Candidate> candidates, Limits limits) {
        try {
            Response response = client.post().uri("/v1/scheduler/plan").contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(candidates, limits)).retrieve().body(Response.class);
            return response == null || response.bookingIds() == null ? Optional.empty()
                    : Optional.of(List.copyOf(response.bookingIds()));
        } catch (RestClientException e) {
            log.warn("The contact scheduler did not answer: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
