package com.dypcet.vayora.stopover;

import com.dypcet.vayora.audit.AuditService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Places to stop on the way: rest stops, and chargers for electric cars. Administrators
 * enter them; none ship with the platform, and nothing checks that a charger works or is
 * open. A driver planning a route is shown the ones near it.
 */
@Service
public class StopoverService {

    public enum Kind { REST, CHARGER }

    /** @param chargerKw the charger's stated power; null for a rest stop, or if not known */
    public record Stopover(long id, Kind kind, String name, double lat, double lon, BigDecimal chargerKw,
            String notes) {
    }

    public record NewStopover(Kind kind, String name, double lat, double lon, BigDecimal chargerKw, String notes) {
    }

    /**
     * A stopover near a route.
     *
     * @param kmAlong      how far along the route it is, from the first stop; approximate
     * @param kmOffRoute   how far from the route, in a straight line
     * @param suggestedBreak the rest stop nearest the point where the driver has driven for
     *                     the configured time; at most one per route, and only on a route
     *                     longer than that
     */
    public record Along(Stopover stopover, double kmAlong, double kmOffRoute, boolean suggestedBreak) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final double withinMetres;
    private final Duration breakAfter;

    public StopoverService(JdbcTemplate jdbc, AuditService audit,
            @Value("${vayora.route.stopovers-within-km}") double withinKm,
            @Value("${vayora.route.break-after}") Duration breakAfter) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.withinMetres = withinKm * 1000.0;
        this.breakAfter = breakAfter;
    }

    public List<Stopover> all() {
        return jdbc.query(SELECT + " ORDER BY kind, name", (rs, n) -> stopover(rs));
    }

    /**
     * The stopovers within the configured distance of a route, in the order the driver
     * reaches them.
     *
     * @param path            the route as [lat, lon] points
     * @param distanceKm      the route's length
     * @param durationMinutes the route's driving time, to place a suggested break
     */
    public List<Along> along(List<double[]> path, double distanceKm, int durationMinutes) {
        if (path == null || path.size() < 2) {
            return List.of();
        }
        String line = path.stream().map(p -> p[1] + " " + p[0]).collect(Collectors.joining(",", "LINESTRING(", ")"));
        List<Along> found = jdbc.query("""
                WITH route AS (SELECT ST_GeomFromText(?, 4326) AS g)
                SELECT s.id, s.kind, s.name, ST_Y(s.location::geometry) AS lat, ST_X(s.location::geometry) AS lon,
                       s.charger_kw, s.notes,
                       ST_LineLocatePoint(route.g, s.location::geometry) * ST_Length(route.g::geography) AS along_m,
                       ST_Distance(s.location, route.g::geography) AS off_m
                FROM stopovers s, route
                WHERE ST_DWithin(s.location, route.g::geography, ?)
                ORDER BY along_m""",
                (rs, n) -> new Along(stopover(rs), rs.getDouble("along_m") / 1000.0, rs.getDouble("off_m") / 1000.0,
                        false),
                line, withinMetres);
        return withSuggestedBreak(found, distanceKm, durationMinutes);
    }

    /** Marks the rest stop nearest the point the break is due, if the route is long enough to need one. */
    private List<Along> withSuggestedBreak(List<Along> found, double distanceKm, int durationMinutes) {
        if (durationMinutes <= breakAfter.toMinutes() || found.isEmpty()) {
            return found;
        }
        // Driving at an even pace, the break falls this far along the route.
        double dueKm = distanceKm * breakAfter.toMinutes() / durationMinutes;
        Along best = null;
        for (Along a : found) {
            if (a.stopover().kind() == Kind.REST
                    && (best == null || Math.abs(a.kmAlong() - dueKm) < Math.abs(best.kmAlong() - dueKm))) {
                best = a;
            }
        }
        Along chosen = best;
        return found.stream()
                .map(a -> a == chosen ? new Along(a.stopover(), a.kmAlong(), a.kmOffRoute(), true) : a)
                .toList();
    }

    @Transactional
    public Stopover add(long adminId, NewStopover stop) {
        if (stop.kind() == Kind.REST && stop.chargerKw() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a charger has a power rating");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO stopovers (kind, name, location, charger_kw, notes, created_by)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?) RETURNING id""",
                Long.class, stop.kind().name(), stop.name().strip(), stop.lon(), stop.lat(), stop.chargerKw(),
                stop.notes() == null || stop.notes().isBlank() ? null : stop.notes().strip(), adminId);
        audit.record(adminId, "STOPOVER_ADDED", "stopover", id, Map.of("kind", stop.kind().name(), "name", stop.name()));
        return jdbc.queryForObject(SELECT + " WHERE id = ?", (rs, n) -> stopover(rs), id);
    }

    @Transactional
    public void remove(long adminId, long id) {
        if (jdbc.update("DELETE FROM stopovers WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stopover");
        }
        audit.record(adminId, "STOPOVER_REMOVED", "stopover", id, Map.of());
    }

    private static final String SELECT = """
            SELECT id, kind, name, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, charger_kw, notes
            FROM stopovers""";

    private static Stopover stopover(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stopover(rs.getLong("id"), Kind.valueOf(rs.getString("kind")), rs.getString("name"),
                rs.getDouble("lat"), rs.getDouble("lon"), rs.getBigDecimal("charger_kw"), rs.getString("notes"));
    }
}
