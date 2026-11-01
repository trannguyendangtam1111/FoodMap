package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Goong photo reference. Resolve to a real URL via:
 *   {baseUrl}/Place/photo?photo_reference={ref}&maxwidth={w}&api_key={mapKey}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoongPhotoDTO(
    String photoReference,
    Integer width,
    Integer height
) {}
