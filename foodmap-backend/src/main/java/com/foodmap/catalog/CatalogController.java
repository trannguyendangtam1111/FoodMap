package com.foodmap.catalog;

import com.foodmap.catalog.dto.CatalogResponse;
import com.foodmap.catalog.dto.CatalogResponse.CatalogAreaDTO;
import com.foodmap.catalog.dto.CatalogResponse.CatalogCategoryDTO;
import com.foodmap.catalog.dto.CatalogResponse.CatalogFoodDTO;
import com.foodmap.catalog.dto.FoodMatchResponse;
import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.decision.scope.AreaSupplyService;
import com.foodmap.food.match.FoodMatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The server-owned food vocabulary and discovery areas, as clients may see
 * them. Read-only and cacheable. Foods are static for the application's
 * lifetime; the area list leaves out areas that currently have too few
 * restaurants (AreaSupplyService, re-measured at most once a minute), so the
 * ETag covers both.
 */
@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog", description = "Food vocabulary and discovery areas (public fields only)")
public class CatalogController {

    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_MATCHES = 8;

    private final FoodVocabulary vocabulary;
    private final FoodMatcher matcher;
    private final AreaPresets areaPresets;
    private final AreaSupplyService areaSupply;
    private final List<CatalogFoodDTO> foods;
    private final List<CatalogCategoryDTO> categories;

    public CatalogController(FoodVocabulary vocabulary, FoodMatcher matcher, AreaPresets areaPresets,
                             AreaSupplyService areaSupply) {
        this.vocabulary = vocabulary;
        this.matcher = matcher;
        this.areaPresets = areaPresets;
        this.areaSupply = areaSupply;
        this.foods = vocabulary.entries().stream()
            .map(e -> new CatalogFoodDTO(e.key(), e.label(), e.kind().name(), e.parentCategory().key(), e.tone(),
                e.discoverable(), e.order(), e.artKey()))
            .toList();
        this.categories = Arrays.stream(FoodCategory.values())
            .map(c -> new CatalogCategoryDTO(c.key(), c.defaultTone()))
            .toList();
    }

    /** GET /api/v1/catalog — 304 when If-None-Match carries the current ETag. */
    @GetMapping
    @Operation(summary = "Food vocabulary (keys, labels, kinds, categories, tones — no keywords) and the "
        + "discovery areas currently offered (key, label, order — no coordinates)")
    public ResponseEntity<ApiResponse<CatalogResponse>> catalog(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<CatalogAreaDTO> areas = areaSupply.availableAreas().stream()
            .map(a -> new CatalogAreaDTO(a.key(), a.label(), a.order()))
            .toList();
        String etag = "\"" + vocabulary.version() + "-" + areasTag(areas) + "\"";

        CacheControl cache = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
        if (ifNoneMatch != null && Arrays.stream(ifNoneMatch.split(",")).map(String::strip)
                .anyMatch(t -> t.equals(etag) || t.equals("W/" + etag) || t.equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        CatalogResponse body = new CatalogResponse(vocabulary.version(), foods, areas, categories);
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(ApiResponse.ok(body));
    }

    /** GET /api/v1/catalog/foods/match?q= — resolved on the server, so clients need no keyword list. */
    @GetMapping("/foods/match")
    @Operation(summary = "Discoverable foods a search query refers to, and the one food the whole query names (intent)")
    public ApiResponse<FoodMatchResponse> match(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) throw new BadRequestException("q must not be blank.");
        if (q.strip().length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        List<FoodMatchResponse.FoodRef> foods = matcher.matchQuery(q.strip(), MAX_MATCHES).stream()
            .map(e -> new FoodMatchResponse.FoodRef(e.key(), e.label()))
            .toList();
        // The food the whole query names, if any — a search for it opens that food (never a guess)
        FoodMatchResponse.FoodRef intent = matcher.resolveQuery(q.strip())
            .filter(FoodEntry::discoverable)
            .map(e -> new FoodMatchResponse.FoodRef(e.key(), e.label()))
            .orElse(null);
        return ApiResponse.ok(new FoodMatchResponse(foods, intent));
    }

    /** Changes when the area file or the set of offered areas changes. */
    private String areasTag(List<CatalogAreaDTO> areas) {
        String state = areaPresets.version() + ":" + areas.stream().map(CatalogAreaDTO::key).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
