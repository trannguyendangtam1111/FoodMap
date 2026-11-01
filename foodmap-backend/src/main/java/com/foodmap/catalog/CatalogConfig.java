package com.foodmap.catalog;

import com.foodmap.food.match.FoodMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads the food and area catalogs once at startup; an invalid catalog stops startup. */
@Configuration
public class CatalogConfig {

    @Bean
    public FoodVocabulary foodVocabulary() {
        return FoodCatalogLoader.loadFromClasspath();
    }

    @Bean
    public AreaPresets areaPresets() {
        return AreaCatalogLoader.loadFromClasspath();
    }

    @Bean
    public FoodMatcher foodMatcher(FoodVocabulary foodVocabulary) {
        return new FoodMatcher(foodVocabulary);
    }
}
