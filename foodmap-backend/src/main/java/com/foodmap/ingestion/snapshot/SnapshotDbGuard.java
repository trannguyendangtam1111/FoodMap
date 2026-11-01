package com.foodmap.ingestion.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AUTO's database safety checks:
 *
 *   identity   the JDBC URL's host:port/database must be the allowed one
 *              (app.snapshot.auto.allowed-db) — before any Goong request
 *   per wave   compared with the run's start: restaurants grew by exactly the
 *              run's inserts, existing restaurant rows changed only by the
 *              run's re-keys, every other table (place_cells included) has
 *              the same row count
 */
public interface SnapshotDbGuard {

    /** host:port/database of the connected database, normalised (see {@link #normalise}). */
    String identity();

    Baseline baseline();

    /** Problems found (empty = safe), after {@code created} inserts and {@code rekeyed} updates since the baseline. */
    List<String> verify(Baseline baseline, int created, int rekeyed);

    /** Row counts of every table, the highest restaurant id and the time, at the start of the run. */
    record Baseline(Map<String, Long> tableCounts, long maxRestaurantId, Instant at) {}

    String RESTAURANTS = "restaurants";

    /**
     * jdbc:postgresql://127.0.0.1:5434/foodmap_dev?x=y → localhost:5434/foodmap_dev (default port 5432);
     * other URLs: the part after "jdbc:" up to ';' or '?' (e.g. h2:mem:testdb). Lower case.
     */
    static String normalise(String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:")) u = u.substring(5);
        if (u.startsWith("postgresql://")) {
            String rest = u.substring("postgresql://".length());
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            int slash = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            String db = slash >= 0 ? rest.substring(slash + 1) : "";
            String host = hostPort;
            String port = "5432";
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && !hostPort.endsWith("]")) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon + 1);
            }
            if (host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1")) host = "localhost";
            return host + ":" + port + "/" + db;
        }
        int cut = u.length();
        for (char c : new char[]{';', '?'}) {
            int i = u.indexOf(c);
            if (i >= 0) cut = Math.min(cut, i);
        }
        return u.substring(0, cut);
    }
}
