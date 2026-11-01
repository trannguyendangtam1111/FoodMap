package com.foodmap.restaurant.search;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.text.VietnameseText;
import com.foodmap.config.properties.SearchProperties;
import com.foodmap.food.match.FoodClassification;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.dto.SearchCandidateRow;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GET /api/v1/restaurants/search — strictly read-only.
 *
 *   1. PostgreSQL returns a bounded candidate set (folded name/address/menu
 *      match, see RestaurantRepository#findSearchCandidates). When the query
 *      names a catalog food ({@link FoodMatcher#resolveQuery}), a second
 *      bounded query adds rows carrying any of that food's keywords.
 *   2. One batched query loads the FoodMap menu dish names of those candidates.
 *   3. {@link SearchRanker} orders them with the Vietnamese accent rules and
 *      the food intent, if any.
 *   4. One query loads the restaurants of the requested page; they are
 *      classified with the menus from step 2 (no further query).
 *
 * No ingestion, no provider call, no write: this service depends only on
 * read repositories and runs in a read-only transaction. A user location only
 * breaks ties between equally relevant results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantSearchService {

    private final RestaurantRepository restaurantRepository;
    private final FoodClassifier foodClassifier;
    private final RestaurantMapper restaurantMapper;
    private final SearchProperties searchProperties;

    /** Ranked results of all pages, plus the menus already loaded for them. */
    record Ranking(List<SearchRanker.Ranked> ranked, Map<Long, List<String>> menus) {}

    public PageResponse<RestaurantSummaryDTO> search(RestaurantSearchRequest req) {
        Ranking ranking = rank(req);
        List<SearchRanker.Ranked> ranked = ranking.ranked();

        int from = Math.min(req.page() * req.size(), ranked.size());
        int to = Math.min(from + req.size(), ranked.size());
        List<Long> pageIds = ranked.subList(from, to).stream().map(r -> r.candidate().id()).toList();

        Map<Long, Restaurant> byId = pageIds.isEmpty() ? Map.of()
            : restaurantRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(Restaurant::getId, Function.identity()));
        List<Restaurant> pageRows = pageIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<Long, FoodClassification> food = foodClassifier.classifyAll(pageRows, ranking.menus());
        List<RestaurantSummaryDTO> content = pageRows.stream()
            .map(r -> restaurantMapper.toSummary(r, food.get(r.getId())))
            .toList();

        var page = new PageImpl<>(content, PageRequest.of(req.page(), req.size()), ranked.size());
        return PageResponse.of(content, page);
    }

    /** Stages 1–3: candidates → menus → ranked list (all results, before paging). */
    Ranking rank(RestaurantSearchRequest req) {
        String q = req.query();
        if (VietnameseText.tokenize(q).isEmpty()) return new Ranking(List.of(), Map.of());   // e.g. "%" or "!!"
        String escaped = escapeLike(q.replaceAll("\\s+", " "));
        int cap = Math.max(1, searchProperties.getCandidateCap());

        List<SearchCandidateRow> rows = req.origin() == null
            ? restaurantRepository.findSearchCandidates(q, "%" + escaped + "%", escaped + "%", cap)
            : restaurantRepository.findSearchCandidatesNear(q, "%" + escaped + "%", escaped + "%",
                  req.origin().lat(), req.origin().lng(), cap);

        FoodMatcher matcher = foodClassifier.matcher();
        Optional<FoodEntry> food = matcher.resolveQuery(q);
        if (food.isPresent()) {
            Map<Long, SearchCandidateRow> union = new LinkedHashMap<>();
            rows.forEach(r -> union.put(r.getId(), r));
            restaurantRepository.findFoodSearchCandidates(q, likePatterns(food.get()), cap)
                .forEach(r -> union.putIfAbsent(r.getId(), r));
            rows = List.copyOf(union.values());
        }

        Map<Long, List<String>> menus = foodClassifier.menusOf(rows.stream().map(SearchCandidateRow::getId).toList());

        List<SearchRanker.Candidate> candidates = rows.stream()
            .map(row -> new SearchRanker.Candidate(
                row.getId(), row.getName(), row.getAddress(),
                row.getAvgRating() == null ? BigDecimal.ZERO : row.getAvgRating(),
                row.getReviewCount() == null ? 0 : row.getReviewCount(),
                row.getLatitude(), row.getLongitude(),
                row.getNameSimilarity() == null ? 0.0 : row.getNameSimilarity(),
                menus.getOrDefault(row.getId(), List.of())))
            .toList();

        FoodIntent intent = food.map(e -> intentFor(e, matcher)).orElse(null);
        List<SearchRanker.Ranked> ranked = SearchRanker.rank(q, candidates, req.origin(), intent);
        log.debug("search: candidates={} (cap {}) ranked={} origin={} food={}",
            rows.size(), cap, ranked.size(), req.origin() != null, food.map(FoodEntry::key).orElse(null));
        return new Ranking(ranked, menus);
    }

    /** '%kw%' for each keyword, folded like the indexed name expression, joined with '|'. */
    static String likePatterns(FoodEntry food) {
        return food.keywords().stream()
            .map(k -> "%" + VietnameseText.fold(k.text()) + "%")
            .distinct()
            .collect(Collectors.joining("|"));
    }

    static FoodIntent intentFor(FoodEntry food, FoodMatcher matcher) {
        return new FoodIntent() {
            @Override public String foodKey() {
                return food.key();
            }
            @Override public boolean hasEvidence(SearchRanker.Candidate c) {
                return matcher.nameMatches(food, c.name()) || matcher.menuMatch(food, c.dishNames()).isPresent();
            }
            @Override public boolean isAccentVariant(List<String> matchedNameTokens) {
                return matcher.isAccentVariant(food, matchedNameTokens);
            }
        };
    }

    /** Escapes LIKE wildcards so the query is matched literally (ESCAPE '\'). */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
