package com.foodmap.integration.goong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoongGeometry(
    GoongLocation location
) {}
