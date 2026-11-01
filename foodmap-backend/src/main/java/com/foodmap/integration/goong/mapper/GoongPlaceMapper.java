package com.foodmap.integration.goong.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.config.properties.GoongProperties;
import com.foodmap.integration.goong.dto.GoongOpeningHoursDTO;
import com.foodmap.integration.goong.dto.GoongPhotoDTO;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.restaurant.domain.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates Goong DTOs to/from Restaurant entity.
 *
 * The mapper is intentionally explicit about which fields Goong owns
 * (cached provider data) vs which fields FoodMap owns (rating aggregates,
 * cuisine_type, price_level). Merge logic preserves our editorial data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoongPlaceMapper {

    private static final int PHOTO_MAX_WIDTH = 800;
    private static final String[] WEEKDAYS = {
        "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday"
    };

    private final ObjectMapper objectMapper;
    private final GoongProperties goongProperties;

    /**
     * Build a new Restaurant entity from a Goong payload (for first-time inserts).
     */
    public Restaurant toEntity(GoongPlaceDTO dto) {
        Restaurant r = Restaurant.builder()
            .goongPlaceId(dto.placeId())
            .build();
        applyProviderFields(r, dto);
        return r;
    }

    /**
     * Merge Goong fields into an existing Restaurant.
     * Only updates fields that Goong owns — never touches rating, dishes,
     * review_count, cuisine_type, price_level, or is_active.
     */
    public void mergeInto(Restaurant existing, GoongPlaceDTO dto) {
        applyProviderFields(existing, dto);
    }

    // ── Internal ──────────────────────────────────────────

    private void applyProviderFields(Restaurant r, GoongPlaceDTO dto) {
        if (StringUtils.hasText(dto.name())) {
            r.setName(dto.name());
        }
        if (StringUtils.hasText(dto.formattedAddress())) {
            r.setAddress(dto.formattedAddress());
        }
        if (dto.geometry() != null && dto.geometry().location() != null) {
            r.setLatitude(dto.geometry().location().lat());
            r.setLongitude(dto.geometry().location().lng());
        }
        if (StringUtils.hasText(dto.internationalPhoneNumber())) {
            r.setPhone(dto.internationalPhoneNumber());
        }
        if (StringUtils.hasText(dto.website())) {
            r.setWebsite(dto.website());
        }

        Map<String, String> hours = toOpeningHoursMap(dto.openingHours());
        if (!hours.isEmpty()) {
            r.setOpeningHours(hours);
        }

        List<String> photoUrls = toPhotoUrls(dto.photos());
        if (!photoUrls.isEmpty()) {
            r.setPhotos(photoUrls);
        }

        r.setProviderRaw(serializeRaw(dto));
    }

    private Map<String, String> toOpeningHoursMap(GoongOpeningHoursDTO openingHours) {
        Map<String, String> map = new HashMap<>();
        if (openingHours == null || openingHours.weekdayText() == null) {
            return map;
        }
        List<String> weekdayText = openingHours.weekdayText();
        for (int i = 0; i < weekdayText.size() && i < WEEKDAYS.length; i++) {
            String line = weekdayText.get(i);
            int colonIdx = line.indexOf(':');
            String value = colonIdx > 0 && colonIdx + 1 < line.length()
                ? line.substring(colonIdx + 1).trim()
                : line.trim();
            map.put(WEEKDAYS[i].toLowerCase(Locale.ROOT), value);
        }
        return map;
    }

    private List<String> toPhotoUrls(List<GoongPhotoDTO> photos) {
        if (photos == null) return List.of();
        return photos.stream()
            .filter(p -> StringUtils.hasText(p.photoReference()))
            .map(p -> buildPhotoUrl(p.photoReference()))
            .toList();
    }

    /**
     * Build the resolvable photo URL using the public map_key
     * (safe to expose to frontend, unlike api_key).
     */
    private String buildPhotoUrl(String photoReference) {
        return String.format("%s/place/photo?photo_reference=%s&maxwidth=%d&api_key=%s",
            goongProperties.getBaseUrl(),
            photoReference,
            PHOTO_MAX_WIDTH,
            goongProperties.getMapKey()
        );
    }

    private String serializeRaw(GoongPlaceDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize Goong provider_raw for place_id={}", dto.placeId(), e);
            return null;
        }
    }
}
