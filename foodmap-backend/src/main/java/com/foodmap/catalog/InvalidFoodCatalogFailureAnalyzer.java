package com.foodmap.catalog;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Short "APPLICATION FAILED TO START" report for a broken food catalog. */
public class InvalidFoodCatalogFailureAnalyzer extends AbstractFailureAnalyzer<InvalidFoodCatalogException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidFoodCatalogException cause) {
        return new FailureAnalysis(cause.getMessage(),
            "Fix " + FoodCatalogLoader.RESOURCE + " (see the field rules at the top of the file).", cause);
    }
}
