package com.foodmap.catalog;

import java.util.List;

/**
 * One food of the server-owned vocabulary. Immutable.
 *
 * @param tone        resolved tone (the entry's own, else the category default)
 * @param keywords    name/menu evidence terms — server-private
 * @param suppressors words that cancel this entry's match in the same text — server-private
 * @param labelTerm   the label as a query term (used only to resolve queries)
 * @param order       curated display order; null for classification-only entries
 * @param index       position in the catalog's canonical order (discoverable by
 *                    order, then classification-only in file order)
 */
public record FoodEntry(
    String key,
    String label,
    FoodKind kind,
    FoodCategory parentCategory,
    String tone,
    List<FoodTerm> keywords,
    List<FoodTerm> suppressors,
    FoodTerm labelTerm,
    boolean discoverable,
    Integer order,
    String artKey,
    int index
) {}
