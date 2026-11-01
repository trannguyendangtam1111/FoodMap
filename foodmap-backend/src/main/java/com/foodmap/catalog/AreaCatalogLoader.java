package com.foodmap.catalog;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates {@code classpath:catalog/area-catalog.yml}.
 *
 * Any problem throws {@link InvalidAreaCatalogException} naming the area and
 * the rule, so the application refuses to start with a broken area list:
 * unknown fields, invalid or duplicate key, blank or duplicate label, missing
 * or duplicate order, a centre outside valid coordinates, a radius outside
 * {@value #MIN_RADIUS_M}..{@value #MAX_RADIUS_M} m, a non-positive minimum.
 */
public final class AreaCatalogLoader {

    public static final String RESOURCE = "catalog/area-catalog.yml";

    /** Same ceiling as a user-supplied radius (app.decision.max-radius-m). */
    static final int MAX_RADIUS_M = 5000;
    static final int MIN_RADIUS_M = 100;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> TOP_FIELDS = Set.of("minimumRestaurants", "areas");
    private static final Set<String> FIELDS = Set.of("key", "label", "order", "center", "radiusMeters");

    private AreaCatalogLoader() {}

    public static AreaPresets loadFromClasspath() {
        try (InputStream in = AreaCatalogLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new InvalidAreaCatalogException("Area catalog not found on the classpath: " + RESOURCE);
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new InvalidAreaCatalogException("Area catalog could not be read: " + e.getMessage());
        }
    }

    public static AreaPresets parse(byte[] content) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(content, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw fail("the file is not valid YAML (" + e.getClass().getSimpleName() + ")");
        }
        if (!(root instanceof Map<?, ?> map)) throw fail("expected 'minimumRestaurants' and 'areas' at the top level");
        for (Object field : map.keySet()) {
            if (!TOP_FIELDS.contains(String.valueOf(field))) throw fail("unknown top-level field '" + field + "'");
        }
        if (!(map.get("minimumRestaurants") instanceof Integer minimum) || minimum <= 0) {
            throw fail("minimumRestaurants must be a positive integer");
        }
        if (!(map.get("areas") instanceof List<?> rawAreas) || rawAreas.isEmpty()) {
            throw fail("expected a non-empty 'areas' list");
        }

        List<AreaPreset> areas = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        Map<Integer, String> orders = new HashMap<>();
        for (int i = 0; i < rawAreas.size(); i++) {
            AreaPreset a = parseArea(rawAreas.get(i), i);
            if (areas.stream().anyMatch(other -> other.key().equals(a.key()))) throw fail(a.key(), "duplicate key");
            String labelOwner = labels.putIfAbsent(a.label(), a.key());
            if (labelOwner != null) throw fail(a.key(), "label '" + a.label() + "' is already used by '" + labelOwner + "'");
            String orderOwner = orders.putIfAbsent(a.order(), a.key());
            if (orderOwner != null) throw fail(a.key(), "order " + a.order() + " is already used by '" + orderOwner + "'");
            areas.add(a);
        }
        areas.sort(Comparator.comparingInt(AreaPreset::order));
        return new AreaPresets(areas, minimum, sha256(content).substring(0, 16));
    }

    private static AreaPreset parseArea(Object raw, int position) {
        if (!(raw instanceof Map<?, ?> m)) throw fail("area #" + (position + 1) + " is not a mapping");
        String where = m.get("key") instanceof String k ? k : "area #" + (position + 1);
        for (Object field : m.keySet()) {
            if (!FIELDS.contains(String.valueOf(field))) throw fail(where, "unknown field '" + field + "'");
        }

        if (!(m.get("key") instanceof String key) || !SLUG.matcher(key).matches()) {
            throw fail(where, "key must be a lowercase slug (a-z, 0-9, '-')");
        }
        if (!(m.get("label") instanceof String label) || label.isBlank()) throw fail(where, "'label' is required");
        if (!(m.get("order") instanceof Integer order) || order <= 0) throw fail(where, "order must be a positive integer");

        if (!(m.get("center") instanceof Map<?, ?> center)) throw fail(where, "center must be {lat, lng}");
        for (Object field : center.keySet()) {
            if (!Set.of("lat", "lng").contains(String.valueOf(field))) throw fail(where, "center allows only 'lat' and 'lng'");
        }
        double lat = coordinate(center.get("lat"), -90, 90, where, "center.lat");
        double lng = coordinate(center.get("lng"), -180, 180, where, "center.lng");

        if (!(m.get("radiusMeters") instanceof Integer radius) || radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) {
            throw fail(where, "radiusMeters must be a whole number from " + MIN_RADIUS_M + " to " + MAX_RADIUS_M);
        }
        return new AreaPreset(key, label.strip(), order, lat, lng, radius);
    }

    private static double coordinate(Object raw, double min, double max, String where, String field) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max) {
            throw fail(where, field + " must be a number from " + (int) min + " to " + (int) max);
        }
        return n.doubleValue();
    }

    private static InvalidAreaCatalogException fail(String message) {
        return new InvalidAreaCatalogException("Invalid area catalog (" + RESOURCE + "): " + message);
    }

    private static InvalidAreaCatalogException fail(String area, String message) {
        return fail("area '" + area + "': " + message);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
