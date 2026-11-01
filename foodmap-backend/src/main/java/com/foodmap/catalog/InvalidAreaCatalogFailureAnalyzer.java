package com.foodmap.catalog;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Short "APPLICATION FAILED TO START" report for a broken area catalog. */
public class InvalidAreaCatalogFailureAnalyzer extends AbstractFailureAnalyzer<InvalidAreaCatalogException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidAreaCatalogException cause) {
        return new FailureAnalysis(cause.getMessage(),
            "Fix " + AreaCatalogLoader.RESOURCE + " (see the field rules at the top of the file).", cause);
    }
}
