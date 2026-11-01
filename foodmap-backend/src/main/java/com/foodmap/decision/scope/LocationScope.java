package com.foodmap.decision.scope;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.config.properties.DecisionProperties;

/**
 * Where a decision or availability request looks — exactly one of:
 *
 *   USER         the user's own coordinates (sent with the request, used for
 *                that request only, never stored); real distances may be shown
 *   AREA         a server-owned FoodMap discovery area (area=key); no distance
 *                is ever shown, the area is named instead ("Quanh Bình Thạnh")
 *   CITY_CENTER  no location sent: around the HCMC centre; no distance shown
 *
 * The origin is internal: it is the query centre and the ranking origin, and
 * it never appears in a response. Built only by {@link LocationScopeParser}
 * (requests) or the factories below (server defaults, tests).
 */
public sealed interface LocationScope permits LocationScope.User, LocationScope.Area, LocationScope.CityCenter {

    String SOURCE_USER = "USER";
    String SOURCE_AREA = "AREA";
    String SOURCE_CITY_CENTER = "CITY_CENTER";

    /** USER, AREA or CITY_CENTER (the public {@code locationSource}). */
    String source();

    /** Query centre and ranking origin. Server-internal; never serialised. */
    double originLat();

    double originLng();

    RadiusLadder ladder();

    /** Ready-to-show Vietnamese phrase: "Gần bạn", "Quanh Quận 1", "Quanh trung tâm TP.HCM". */
    String label();

    /** Only a USER scope measures from the user, so only it may show a distance. */
    default boolean showsDistance() {
        return false;
    }

    /** The discovery area for an AREA scope; null otherwise. */
    default AreaPreset area() {
        return null;
    }

    // ── Factories ────────────────────────────────────────────────────────────

    static User user(double lat, double lng, int radiusMeters, DecisionProperties props) {
        return new User(lat, lng, RadiusLadder.user(radiusMeters, props.getWidenFactor(), props.getMaxRadiusM()));
    }

    static Area area(AreaPreset preset) {
        return new Area(preset);
    }

    static CityCenter cityCentre(DecisionProperties props) {
        return new CityCenter(props.getFallbackLat(), props.getFallbackLng(),
            RadiusLadder.widening(props.getFallbackRadiusM(), props.getMaxRadiusM()),
            "Quanh " + props.getFallbackAreaLabel());
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    /** The user's location, for this request only. */
    record User(double originLat, double originLng, RadiusLadder ladder) implements LocationScope {
        @Override public String source() { return SOURCE_USER; }
        @Override public String label() { return "Gần bạn"; }
        @Override public boolean showsDistance() { return true; }

        /** Never the coordinates: scopes can reach logs and error messages. */
        @Override public String toString() { return "User[" + ladder.stages() + "]"; }
    }

    /** A server-owned discovery area; its own radius, never widened. */
    record Area(AreaPreset area) implements LocationScope {
        @Override public String source() { return SOURCE_AREA; }
        @Override public double originLat() { return area.centerLat(); }
        @Override public double originLng() { return area.centerLng(); }
        @Override public RadiusLadder ladder() { return RadiusLadder.fixed(area.radiusMeters()); }
        @Override public String label() { return "Quanh " + area.label(); }
        @Override public String toString() { return "Area[" + area.key() + "]"; }
    }

    /** No location sent: around the city centre, never presented as "near you". */
    record CityCenter(double originLat, double originLng, RadiusLadder ladder, String label) implements LocationScope {
        @Override public String source() { return SOURCE_CITY_CENTER; }
        @Override public String toString() { return "CityCenter[" + ladder.stages() + "]"; }
    }
}
