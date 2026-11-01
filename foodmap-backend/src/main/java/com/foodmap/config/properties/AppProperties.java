package com.foodmap.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.restaurant")
public class AppProperties {

    private int syncTtlDays = 7;
    private int viewportMaxResults = 200;
    private int nearbyDefaultRadiusM = 1500;
    private int nearbyMaxRadiusM = 5000;
}
