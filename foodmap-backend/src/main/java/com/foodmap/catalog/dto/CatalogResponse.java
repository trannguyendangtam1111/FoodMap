package com.foodmap.catalog.dto;

import java.util.List;

/**
 * GET /api/v1/catalog. Public vocabulary only: keywords, suppressors and
 * matching details never leave the server, and neither do area centres or
 * radii.
 *
 * @param version content hash of the food catalog
 * @param areas   FoodMap discovery areas currently offered (enough restaurants), in display order
 */
public record CatalogResponse(String version, List<CatalogFoodDTO> foods, List<CatalogAreaDTO> areas,
                              List<CatalogCategoryDTO> categories) {

    /** One food. {@code order} is absent for classification-only foods. */
    public record CatalogFoodDTO(String key, String label, String kind, String parentCategory, String tone,
                                 boolean discoverable, Integer order, String artKey) {}

    /**
     * One FoodMap discovery area — a search area, not an administrative
     * boundary. Request it as {@code area=key}; shown as "Quanh {label}".
     */
    public record CatalogAreaDTO(String key, String label, int order) {}

    /** One CategoryArt category and its default tone. */
    public record CatalogCategoryDTO(String key, String tone) {}
}
