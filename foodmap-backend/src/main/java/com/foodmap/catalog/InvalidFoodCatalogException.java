package com.foodmap.catalog;

/** The food catalog file is malformed; thrown at startup so the app never runs with it. */
public class InvalidFoodCatalogException extends IllegalStateException {

    public InvalidFoodCatalogException(String message) {
        super(message);
    }
}
