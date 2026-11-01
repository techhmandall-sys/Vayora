package com.dypcet.vayora.pass;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The passes on sale. A pass waives the platform's service fee; it never touches a
 * driver's fare, which is the driver's to set.
 *
 * @param offers each kind once
 */
@ConfigurationProperties("vayora.pass")
public record PassProperties(List<Offer> offers) {

    public enum Kind {
        WEEKLY,
        MONTHLY,
        ANNUAL
    }

    /** @param days how long a pass of this kind lasts from the moment it starts */
    public record Offer(Kind kind, int days, BigDecimal price) {
    }

    public PassProperties {
        if (offers == null || offers.isEmpty()) {
            throw new IllegalArgumentException("vayora.pass.offers must not be empty");
        }
        for (Offer offer : offers) {
            if (offer.kind() == null || offer.days() < 1 || offer.price() == null || offer.price().signum() <= 0) {
                throw new IllegalArgumentException("vayora.pass.offers has an invalid offer");
            }
        }
        if (new HashSet<>(offers.stream().map(Offer::kind).toList()).size() != offers.size()) {
            throw new IllegalArgumentException("vayora.pass.offers lists a kind twice");
        }
        offers = List.copyOf(offers);
    }
}
