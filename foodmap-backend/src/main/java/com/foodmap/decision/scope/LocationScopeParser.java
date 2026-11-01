package com.foodmap.decision.scope;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.DecisionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one interpretation of the scope parameters shared by every
 * /api/v1/decisions endpoint (Master Specification §8.1):
 *
 *   lat + lng [+ radius]   → USER        (radius default 1000, 1..5000)
 *   area=key               → AREA        (a known, currently available area)
 *   nothing                → CITY_CENTER
 *
 * Built from raw query strings so every malformed or contradictory value is a
 * 400 with a clear message — never ignored, never a 500. Rejected:
 *   only one of lat/lng; lat/lng out of range or not a number;
 *   area with lat/lng; radius with an area or without a location;
 *   radius not a whole number in 1..max; an unknown or unavailable area.
 *
 * Blank values count as absent (as in V1). Error messages never echo
 * coordinates; the scope itself is used for the request only.
 */
@Component
@RequiredArgsConstructor
public class LocationScopeParser {

    private final DecisionProperties props;
    private final AreaPresets areaPresets;
    private final AreaSupplyService areaSupply;

    public LocationScope parse(String lat, String lng, String radius, String area) {
        Double parsedLat = parseCoordinate("lat", lat, -90, 90);
        Double parsedLng = parseCoordinate("lng", lng, -180, 180);
        if ((parsedLat == null) != (parsedLng == null)) {
            throw new BadRequestException("lat and lng must be provided together.");
        }
        boolean hasLocation = parsedLat != null;
        boolean hasArea = !isBlank(area);
        boolean hasRadius = !isBlank(radius);

        if (hasArea && hasLocation) {
            throw new BadRequestException("Choose either a location (lat, lng) or an area, not both.");
        }
        if (hasRadius && !hasLocation) {
            throw new BadRequestException(hasArea
                ? "radius cannot be combined with an area; an area has its own radius."
                : "radius needs lat and lng.");
        }

        if (hasLocation) {
            int radiusMeters = hasRadius ? parseRadius(radius) : props.getDefaultRadiusM();
            return LocationScope.user(parsedLat, parsedLng, radiusMeters, props);
        }
        if (hasArea) {
            return LocationScope.area(parseArea(area.strip()));
        }
        return cityCentre();
    }

    public LocationScope cityCentre() {
        return LocationScope.cityCentre(props);
    }

    private AreaPreset parseArea(String key) {
        // Echo only slug-shaped input: whatever else was sent stays out of the response.
        AreaPreset preset = areaPresets.get(key).orElseThrow(() -> new BadRequestException(
            key.matches("[a-z0-9-]{1,40}") ? "Unknown area '" + key + "'." : "Unknown area."));
        if (!areaSupply.isAvailable(preset)) {
            throw new BadRequestException("Area '" + key + "' is not available right now; choose another area.");
        }
        return preset;
    }

    private static Double parseCoordinate(String name, String raw, double min, double max) {
        if (isBlank(raw)) return null;
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be a number.");
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new BadRequestException(name + " must be between " + (int) min + " and " + (int) max + ".");
        }
        return value;
    }

    private int parseRadius(String raw) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("radius must be a whole number of metres.");
        }
        if (value <= 0) {
            throw new BadRequestException("radius must be positive.");
        }
        if (value > props.getMaxRadiusM()) {
            throw new BadRequestException("radius must not exceed " + props.getMaxRadiusM() + " metres.");
        }
        return value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
