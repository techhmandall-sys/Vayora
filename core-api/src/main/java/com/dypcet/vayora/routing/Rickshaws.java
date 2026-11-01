package com.dypcet.vayora.routing;

import com.dypcet.vayora.routing.RickshawProperties.Fare;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.routing.RouteService.Route;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * An auto-rickshaw to or from a stop (feature 91): time and distance by road, and the meter
 * fare where a published one is known. An estimate: the meter, the traffic and the hour
 * decide the real fare, and no rickshaw's availability is known.
 */
@Component
@EnableConfigurationProperties(RickshawProperties.class)
public class Rickshaws {

    /**
     * @param fare       rupees by the town's published meter fare, to the nearest rupee; null
     *                   where none is known or it was not yet in force on the trip's day
     * @param fareTown   the town whose fare it is, and fareSource who published it; null with fare
     * @param byRoad     the time and distance are by road; false when the routing service was
     *                   down and they are a straight line, which is shorter
     */
    public record Estimate(int minutes, double km, Integer fare, String fareTown, String fareSource,
            LocalDate fareSince, boolean byRoad) {
    }

    private final RouteService routes;
    private final RickshawProperties properties;

    public Rickshaws(RouteService routes, RickshawProperties properties) {
        this.routes = routes;
        this.properties = properties;
    }

    /**
     * A rickshaw between a passenger's point and a stop, or nothing if the walk is short.
     *
     * @param stop      the end that is a stop: its town's fare is the one charged
     * @param walkMetres the straight-line walk this would replace
     * @param day       the trip's day, for whether the fare was yet in force
     */
    public Optional<Estimate> between(Point from, Point to, Point stop, double walkMetres, LocalDate day) {
        if (walkMetres < properties.offerBeyondMetres()) {
            return Optional.empty();
        }
        Route route = routes.through(List.of(from, to));
        double km = Math.round(route.distanceMetres() / 100.0) / 10.0;
        int minutes = (int) Math.ceil(route.durationSeconds() / 60.0);
        boolean byRoad = RouteService.OSRM.equals(route.source());
        Optional<Fare> fare = properties.at(stop).filter(f -> !f.since().isAfter(day));
        return Optional.of(new Estimate(minutes, km, fare.map(f -> meter(f, route.distanceMetres() / 1000.0)).orElse(null),
                fare.map(Fare::town).orElse(null), fare.map(Fare::source).orElse(null),
                fare.map(Fare::since).orElse(null), byRoad));
    }

    /** The minimum fare up to its distance, then the rate for every kilometre after, to the rupee. */
    static int meter(Fare fare, double km) {
        // In decimal, not double: 2.275 - 1.5 in doubles is 0.77499..., which loses a half rupee.
        BigDecimal beyond = BigDecimal.valueOf(km).subtract(BigDecimal.valueOf(fare.minimumKm())).max(BigDecimal.ZERO);
        return fare.minimumFare().add(fare.perKm().multiply(beyond)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
