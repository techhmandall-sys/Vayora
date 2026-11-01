package com.dypcet.vayora.routing;

import com.dypcet.vayora.routing.RouteService.Point;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When an auto-rickshaw is offered to or from a stop, and the published meter fares
 * (feature 91). A fare is listed only where its source gives both how far the minimum
 * fare goes and the rate after it; a town without one gets a time but no fare.
 *
 * @param offerBeyondMetres a rickshaw is offered when the walk is at least this far in a
 *                          straight line
 */
@ConfigurationProperties("vayora.rickshaw")
public record RickshawProperties(double offerBeyondMetres, List<Fare> fares) {

    /**
     * One town's meter fare, as published.
     *
     * @param lat       the town's centre, with lon; a stop within radiusKm of it is in the town
     * @param since     the day the fare took effect; a trip before it is not charged by it
     * @param source    who published it and where it was read, shown with every estimate
     */
    public record Fare(String town, double lat, double lon, double radiusKm, BigDecimal minimumFare,
            double minimumKm, BigDecimal perKm, LocalDate since, String source) {
    }

    public RickshawProperties {
        if (offerBeyondMetres < 0) {
            throw new IllegalArgumentException("vayora.rickshaw.offer-beyond-metres must not be negative");
        }
        fares = fares == null ? List.of() : List.copyOf(fares);
        for (Fare fare : fares) {
            if (fare.minimumFare().signum() <= 0 || fare.perKm().signum() <= 0 || fare.minimumKm() < 0
                    || fare.radiusKm() <= 0 || fare.since() == null || fare.source() == null || fare.source().isBlank()) {
                throw new IllegalArgumentException("vayora.rickshaw.fares: " + fare.town()
                        + " needs a positive fare, rate and radius, a start date and a source");
            }
        }
    }

    /** The fare of the nearest town whose centre is within its radius of this point, if any. */
    Optional<Fare> at(Point point) {
        return fares.stream()
                .filter(f -> RouteService.greatCircleMetres(point, new Point(f.lat(), f.lon())) <= f.radiusKm() * 1000)
                .min(Comparator.comparingDouble(f -> RouteService.greatCircleMetres(point, new Point(f.lat(), f.lon()))));
    }
}
