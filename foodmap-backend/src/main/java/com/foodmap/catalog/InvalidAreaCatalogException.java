package com.foodmap.catalog;

/** The area catalog file is malformed; thrown at startup so the app never runs with it. */
public class InvalidAreaCatalogException extends IllegalStateException {

    public InvalidAreaCatalogException(String message) {
        super(message);
    }
}
