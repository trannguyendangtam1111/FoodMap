package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Goong Place/Detail response. Detail-only fields (phone, website, opening_hours) may be null. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoongPlaceDTO(
    String placeId,
    String name,
    String formattedAddress,
    GoongGeometry geometry,
    List<String> types,
    List<GoongPhotoDTO> photos,
    Double rating,
    Integer userRatingsTotal,

    // Detail-only fields
    String internationalPhoneNumber,
    String website,
    GoongOpeningHoursDTO openingHours
) {}
