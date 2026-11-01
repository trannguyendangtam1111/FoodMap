package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoongPlaceDetailResponse(
    String status,
    GoongPlaceDTO result
) {

    public boolean isSuccess() {
        return "OK".equals(status) && result != null;
    }
}
