package com.dypcet.vayora.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dypcet.vayora.hazard.IntelContactScheduler.Candidate;
import com.dypcet.vayora.hazard.IntelContactScheduler.Limits;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The request intel's scheduler takes (intel/app/scheduler/api.py), and its answer, field by field. */
class IntelContactSchedulerTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://intel");
    private final MockRestServiceServer intel = MockRestServiceServer.bindTo(builder).build();
    private final IntelContactScheduler scheduler = new IntelContactScheduler(builder.build());

    private static final List<Candidate> CANDIDATES = List.of(new Candidate(7, 3, 20500, 2, 0.4, 40.5));
    private static final Limits LIMITS = new Limits(2, 1, 4, 168, 5);

    @Test
    void asksInIntelsOwnFieldNamesAndReadsItsAnswer() {
        intel.expect(requestTo("http://intel/v1/scheduler/plan")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"candidates": [{"booking_id": 7, "ride_id": 3, "departure_day": 20500, "seats": 2,
                                         "survival_probability": 0.4, "hours_to_departure": 40.5}],
                         "limits": {"contacts": 2, "contacts_per_ride": 1, "seats_per_departure_day": 4,
                                    "refill_horizon_hours": 168.0, "time_limit_seconds": 5.0}}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"booking_ids\": [7], \"status\": \"OPTIMAL\", \"worth\": 1.2}",
                        MediaType.APPLICATION_JSON));

        assertThat(scheduler.plan(CANDIDATES, LIMITS)).contains(List.of(7L));
        intel.verify();
    }

    @Test
    void aFailingSchedulerIsNoAnswerNotAnError() {
        intel.expect(requestTo("http://intel/v1/scheduler/plan")).andRespond(withServerError());

        assertThat(scheduler.plan(CANDIDATES, LIMITS)).isEmpty();
    }
}
