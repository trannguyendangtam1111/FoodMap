package com.foodmap.common.util;

public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {}

    /**
     * Haversine distance between two lat/lng points, in meters.
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c * 1000;
    }

    /**
     * Returns a bounding box [swLat, swLng, neLat, neLng] for a radius around a center point.
     * Used for fast pre-filter before exact Haversine.
     */
    public static double[] boundingBox(double lat, double lng, double radiusMeters) {
        double latDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_KM * 1000));
        double lngDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_KM * 1000 * Math.cos(Math.toRadians(lat))));
        return new double[]{lat - latDelta, lng - lngDelta, lat + latDelta, lng + lngDelta};
    }

    /**
     * Computes the center lat/lng of a viewport bounding box.
     */
    public static double[] viewportCenter(double swLat, double swLng, double neLat, double neLng) {
        return new double[]{(swLat + neLat) / 2.0, (swLng + neLng) / 2.0};
    }

    /**
     * Approximate radius in meters that covers a viewport bounding box.
     */
    public static double viewportRadius(double swLat, double swLng, double neLat, double neLng) {
        double[] center = viewportCenter(swLat, swLng, neLat, neLng);
        return distanceMeters(center[0], center[1], neLat, neLng);
    }

    /**
     * 6-character geohash prefix for region bucketing (approx 1.2km x 0.6km cells).
     * Lightweight implementation — does not depend on external libraries.
     */
    public static String geohash(double lat, double lng, int precision) {
        final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};

        StringBuilder hash = new StringBuilder();
        int bits = 0;
        int bitsTotal = 0;
        int hashValue = 0;
        boolean isEven = true;

        while (hash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lngRange[0] + lngRange[1]) / 2;
                if (lng > mid) { hashValue = (hashValue << 1) | 1; lngRange[0] = mid; }
                else           { hashValue = hashValue << 1;        lngRange[1] = mid; }
            } else {
                mid = (latRange[0] + latRange[1]) / 2;
                if (lat > mid) { hashValue = (hashValue << 1) | 1; latRange[0] = mid; }
                else           { hashValue = hashValue << 1;        latRange[1] = mid; }
            }
            isEven = !isEven;
            if (++bits == 5) {
                hash.append(BASE32.charAt(hashValue));
                bits = 0;
                hashValue = 0;
            }
            bitsTotal++;
        }
        return hash.toString();
    }
}
