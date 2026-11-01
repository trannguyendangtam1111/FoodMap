package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One prediction row from Goong AutoComplete. place_id is load-bearing for
 * the discovery pipeline; description is kept for debug logging. The
 * structured_formatting texts (place name, address) let a snapshot run
 * recognise an already-known restaurant before paying for Place Detail.
 * Goong documents no coordinates on predictions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoongPrediction(
    String placeId,
    String description,
    StructuredFormatting structuredFormatting
) {

    public GoongPrediction(String placeId, String description) {
        this(placeId, description, null);
    }

    /** The place's name (main_text), or null. */
    public String mainText() {
        return structuredFormatting == null ? null : structuredFormatting.mainText();
    }

    /** The place's address (secondary_text), or null. */
    public String secondaryText() {
        return structuredFormatting == null ? null : structuredFormatting.secondaryText();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StructuredFormatting(String mainText, String secondaryText) {}
}
