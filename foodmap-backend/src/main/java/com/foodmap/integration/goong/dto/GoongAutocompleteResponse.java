package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoongAutocompleteResponse(
    String status,
    List<GoongPrediction> predictions
) {

    public static final String STATUS_OK           = "OK";
    public static final String STATUS_ZERO_RESULTS = "ZERO_RESULTS";

    public boolean isSuccess() {
        return STATUS_OK.equals(status) || STATUS_ZERO_RESULTS.equals(status);
    }
}
