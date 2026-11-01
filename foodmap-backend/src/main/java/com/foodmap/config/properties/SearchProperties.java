package com.foodmap.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Restaurant search tuning. Override under "app.search" in application.yml.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    /**
     * Upper bound on stage-1 candidates read from PostgreSQL before the Java
     * ranker orders them. Results past this cap are never returned, so keep it
     * well above the page sizes clients request.
     */
    private int candidateCap = 300;
}
