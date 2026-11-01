package com.foodmap.decision.controller;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.common.exception.ResourceNotFoundException;
import com.foodmap.common.dto.ErrorResponse;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.DecisionResponse;
import com.foodmap.decision.ratelimit.BrowseRateLimiter;
import com.foodmap.decision.ratelimit.DecisionRateLimiter;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.decision.scope.LocationScopeParser;
import com.foodmap.decision.service.DecisionRequest;
import com.foodmap.decision.service.DecisionService;
import com.foodmap.decision.service.FoodAvailabilityService;
import com.foodmap.decision.service.FoodDecisionRequest;
import com.foodmap.decision.service.FoodDecisionService;
import com.foodmap.decision.service.FoodRestaurantsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Set;

/**
 * "Ăn gì hôm nay?" — the server decides, the frontend only presents.
 *
 * Every endpoint takes the same scope parameters, interpreted once by
 * {@link LocationScopeParser}: lat + lng [+ radius] (USER), area=key (AREA),
 * or none (CITY_CENTER). Contradictory or malformed scopes are a 400.
 *
 * Anonymous, read-only, rate-limited per client IP. Coordinates are used for
 * the request only: never stored, never echoed, never logged above DEBUG.
 */
@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
@Tag(name = "Decisions", description = "\"Ăn gì hôm nay?\" — server-side food and restaurant pick")
public class DecisionController {

    private static final String LAT_DOC = "User latitude (-90..90): USER scope. Send together with lng; not with area.";
    private static final String LNG_DOC = "User longitude (-180..180): USER scope. Send together with lat; not with area.";
    private static final String RADIUS_DOC = "USER scope only: search radius in metres (1..5000, default 1000); "
        + "widened once ×3 (≤ 5000) when nothing is found.";
    private static final String AREA_DOC = "FoodMap discovery area key from GET /api/v1/catalog (e.g. binh-thanh): "
        + "AREA scope, never widened, no distances. Not with lat/lng/radius.";

    private final DecisionService decisionService;
    private final DecisionRateLimiter rateLimiter;
    private final DecisionProperties props;
    private final BrowseRateLimiter browseRateLimiter;
    private final FoodAvailabilityService foodAvailabilityService;
    private final FoodDecisionService foodDecisionService;
    private final FoodRestaurantsService foodRestaurantsService;
    private final FoodVocabulary foodVocabulary;
    private final LocationScopeParser scopeParser;

    /**
     * GET /api/v1/decisions/today?lat=&lng=&radius=1000&mood=mon-nuoc&exclude=12,40
     * GET /api/v1/decisions/today?food=pho&area=binh-thanh&exclude=12,40   (food-first)
     */
    @GetMapping("/today")
    @Operation(
        summary = "Pick one restaurant for \"Ăn gì hôm nay?\"",
        description = """
            Anonymous and read-only (never triggers ingestion or provider calls).
            Scope: lat+lng[+radius] (USER: requested radius, widened once ×3 ≤ 5000), area=key (AREA: the
            area's radius, never widened) or nothing (CITY_CENTER: 3 km, widened to 5 km). The server ranks
            the scope's restaurants with the existing ranking formula and draws one from the top 10 with
            linearly decreasing weights. distanceMeters is the real distance from the user, USER scope only.
            Fallbacks: 0 exact · 1 mood relaxed · 2 radius widened · 3 city centre.
            No candidate at all → 200 with restaurant = null.
            With food=<key>: only restaurants whose name or FoodMap menu names that food. The food is never
            relaxed or replaced and the scope never changes; none → restaurant = null with emptyReason
            NO_FOOD_IN_SCOPE (or ALL_SHOWN when every candidate is excluded). food cannot be combined with mood.
            Rate limit: token bucket per client IP (default 20 burst, 20/min);
            exceeding it returns 429 with a Retry-After header.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Decision made, or no candidate (restaurant = null)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Invalid or contradictory lat/lng/radius/area, or invalid mood/exclude/food"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
            description = "Too many requests from this client; see Retry-After"),
    })
    public ResponseEntity<?> today(
            @Parameter(description = LAT_DOC) @RequestParam(required = false) String lat,
            @Parameter(description = LNG_DOC) @RequestParam(required = false) String lng,
            @Parameter(description = RADIUS_DOC) @RequestParam(required = false) String radius,
            @Parameter(description = AREA_DOC) @RequestParam(required = false) String area,
            @Parameter(description = "Optional mood. Supported: mon-nuoc")
            @RequestParam(required = false) String mood,
            @Parameter(description = "Comma-separated restaurant IDs never to return (max 50)")
            @RequestParam(required = false) String exclude,
            @Parameter(description = "Optional catalog food key (e.g. pho): pick a restaurant with that food")
            @RequestParam(required = false) String food,
            HttpServletRequest http
    ) {
        DecisionRateLimiter.Result limit = rateLimiter.tryConsume(http.getRemoteAddr());
        if (!limit.allowed()) return tooManyRequests(limit, http);

        if (food != null && !food.isBlank()) {
            if (mood != null && !mood.isBlank()) {
                throw new BadRequestException("food and mood cannot be combined.");
            }
            FoodEntry entry = FoodDecisionRequest.parseFood(food, foodVocabulary);
            Set<Long> excludeIds = DecisionRequest.parseExcludeIds(exclude, props.getMaxExcludeIds());
            LocationScope scope = scopeParser.parse(lat, lng, radius, area);
            return ResponseEntity.ok(ApiResponse.ok(foodDecisionService.decideRestaurant(scope, entry, excludeIds)));
        }

        LocationScope scope = scopeParser.parse(lat, lng, radius, area);
        DecisionResponse decision = decisionService.decide(DecisionRequest.parse(scope, mood, exclude, props));
        return ResponseEntity.ok(ApiResponse.ok(decision));
    }

    /**
     * GET /api/v1/decisions/foods/available?kinds=MEAL,DRINK,DESSERT[&lat=&lng=&radius= | &area=]
     *
     * Catalog foods with at least one restaurant in the scope, with real
     * counts, in catalog order.
     */
    @GetMapping("/foods/available")
    @Operation(
        summary = "Foods with at least one restaurant in scope (supply counts, catalog order)",
        description = """
            Anonymous and read-only. A food counts a restaurant when its name or its FoodMap menu names
            the food; counts are supply, not popularity. Scope: lat+lng[+radius] (USER), area=key (AREA)
            or nothing (CITY_CENTER). Each food's count is at the scope's base radius, or — only for foods
            with none there — at the widened radius (USER, CITY_CENTER; an AREA never widens), reported
            per food. Rate limit: browse bucket per client IP (default 60 burst, 60/min).""")
    public ResponseEntity<?> availableFoods(
            @Parameter(description = "Comma-separated kinds: MEAL, DRINK, DESSERT (default all)")
            @RequestParam(required = false) String kinds,
            @Parameter(description = LAT_DOC) @RequestParam(required = false) String lat,
            @Parameter(description = LNG_DOC) @RequestParam(required = false) String lng,
            @Parameter(description = RADIUS_DOC) @RequestParam(required = false) String radius,
            @Parameter(description = AREA_DOC) @RequestParam(required = false) String area,
            HttpServletRequest http
    ) {
        DecisionRateLimiter.Result limit = browseRateLimiter.tryConsume(http.getRemoteAddr());
        if (!limit.allowed()) return tooManyRequests(limit, http);
        Set<FoodKind> parsed = FoodDecisionRequest.parseKinds(kinds, EnumSet.allOf(FoodKind.class));
        LocationScope scope = scopeParser.parse(lat, lng, radius, area);
        return ResponseEntity.ok(ApiResponse.ok(foodAvailabilityService.availableFoods(scope, parsed)));
    }

    /**
     * GET /api/v1/decisions/food?kinds=MEAL&exclude=pho,bun[&lat=&lng=&radius= | &area=]
     *
     * The cà mèn's food: one uniform draw among the foods available in the
     * scope (default kind MEAL), never one already shown. Same decision rate
     * limit as /today.
     */
    @GetMapping("/food")
    @Operation(
        summary = "Pick one FOOD for \"Ăn gì hôm nay?\" (server-side, uniform among available foods)",
        description = """
            Anonymous and read-only. Draws uniformly among the catalog foods that have at least one
            restaurant in the scope — lat+lng[+radius] (USER), area=key (AREA) or nothing (CITY_CENTER) —
            of the requested kinds (default MEAL), minus the comma-separated food keys in exclude
            (max 30; unknown keys are a 400). Returns the food, its restaurant count (supply, not
            popularity) and the radius that count is for. Nothing to offer → 200 with food = null and
            emptyReason NO_FOOD_IN_SCOPE or ALL_SHOWN. Rate limit: the /today decision bucket.""")
    public ResponseEntity<?> food(
            @Parameter(description = "Comma-separated kinds: MEAL, DRINK, DESSERT (default MEAL)")
            @RequestParam(required = false) String kinds,
            @Parameter(description = "Comma-separated food keys already shown (max 30)")
            @RequestParam(required = false) String exclude,
            @Parameter(description = LAT_DOC) @RequestParam(required = false) String lat,
            @Parameter(description = LNG_DOC) @RequestParam(required = false) String lng,
            @Parameter(description = RADIUS_DOC) @RequestParam(required = false) String radius,
            @Parameter(description = AREA_DOC) @RequestParam(required = false) String area,
            HttpServletRequest http
    ) {
        DecisionRateLimiter.Result limit = rateLimiter.tryConsume(http.getRemoteAddr());
        if (!limit.allowed()) return tooManyRequests(limit, http);
        FoodDecisionRequest request = FoodDecisionRequest.parse(kinds, exclude, foodVocabulary);
        LocationScope scope = scopeParser.parse(lat, lng, radius, area);
        return ResponseEntity.ok(ApiResponse.ok(
            foodDecisionService.decideFood(scope, request.kinds(), request.excludedFoods())));
    }

    /**
     * GET /api/v1/decisions/foods/{key}/restaurants?page=0&size=20[&lat=&lng=&radius= | &area=]
     *
     * The FoodPage list: every restaurant of the scope with evidence for the
     * food, in the ranking order (deterministic), paged. Browse rate limit.
     */
    @GetMapping("/foods/{key}/restaurants")
    @Operation(
        summary = "Restaurants FoodMap knows to have a food, in a scope (ranked, paged, deterministic)",
        description = """
            Anonymous and read-only (never triggers ingestion or provider calls). Only restaurants whose
            name or FoodMap menu names the food, each with that evidence (NAME, or MENU with the dish).
            Scope: lat+lng[+radius] (USER), area=key (AREA) or nothing (CITY_CENTER), at the same radius
            GET /decisions/foods/available reports for the food — so totalElements equals its count there.
            Ordered by the existing ranking formula from the user, the area centre or the city centre;
            no random draw, the same request returns the same order. distanceMeters is the real distance
            from the user, USER scope only. The food is never substituted: none in scope → 200 with
            totalElements 0. Unknown food key → 404. Rate limit: browse bucket per client IP.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "One page of the food's restaurants (possibly empty)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Invalid page/size or invalid/contradictory lat/lng/radius/area"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "No such food in the catalog"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
            description = "Too many requests from this client; see Retry-After"),
    })
    public ResponseEntity<?> foodRestaurants(
            @Parameter(description = "Catalog food key (e.g. pho)") @PathVariable String key,
            @Parameter(description = "Zero-based page index (default 0)") @RequestParam(required = false) String page,
            @Parameter(description = "Page size 1..50 (default 20)") @RequestParam(required = false) String size,
            @Parameter(description = LAT_DOC) @RequestParam(required = false) String lat,
            @Parameter(description = LNG_DOC) @RequestParam(required = false) String lng,
            @Parameter(description = RADIUS_DOC) @RequestParam(required = false) String radius,
            @Parameter(description = AREA_DOC) @RequestParam(required = false) String area,
            HttpServletRequest http
    ) {
        DecisionRateLimiter.Result limit = browseRateLimiter.tryConsume(http.getRemoteAddr());
        if (!limit.allowed()) return tooManyRequests(limit, http);
        FoodEntry food = foodVocabulary.get(key)
            .filter(FoodEntry::discoverable)
            .orElseThrow(() -> new ResourceNotFoundException("Unknown food '" + key + "'."));
        int pageIndex = parseInt(page, "page", 0, 0, Integer.MAX_VALUE);
        int pageSize = parseInt(size, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        LocationScope scope = scopeParser.parse(lat, lng, radius, area);
        return ResponseEntity.ok(ApiResponse.ok(foodRestaurantsService.list(scope, food, pageIndex, pageSize)));
    }

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private static int parseInt(String raw, String name, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new BadRequestException(name + " must be an integer between " + min + " and " + max + ".");
    }

    private static ResponseEntity<ErrorResponse> tooManyRequests(DecisionRateLimiter.Result limit, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(limit.retryAfterSeconds()))
            .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please wait a moment and try again.", http.getRequestURI()));
    }
}
