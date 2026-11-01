package com.foodmap.restaurant.search;

import com.foodmap.common.text.VietnameseText;
import com.foodmap.common.text.VietnameseText.PhraseMatch;
import com.foodmap.common.text.VietnameseText.Quality;
import com.foodmap.common.util.GeoUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 2 of restaurant search: deterministic Vietnamese relevance ranking.
 * Pure Java, no database — PostgreSQL only supplies a bounded candidate set.
 *
 * Tiers ({@link SearchTier}), best first:
 *   T1 EXACT_NAME       the name is exactly the query phrase
 *   T2 WHOLE_WORD_NAME  the phrase appears as whole words in the name
 *   T3 FOOD_MENU        the phrase appears in a dish on the FoodMap menu
 *   T4 FUZZY_NAME       last query word as a word prefix ("trà s" → "trà sữa"),
 *                       or trigram similarity ≥ 0.30 — the latter only for
 *                       queries of ≥ 4 folded letters, never across an
 *                       accent conflict ("phở" vs "phố"), and for a
 *                       multi-word query only when every word is itself
 *                       similar to a name word ("bún chả" ↛ "Bún Bò")
 *   T5 ADDRESS_ONLY     the phrase is in the address once a leading
 *                       "{name}, " is removed
 * Accent rules come from {@link VietnameseText}.
 *
 * Single-word band: for a one-token query T1 and T2 rank as one band, so a
 * generic restaurant named just "Phở" does not outrank "Phở Hòa Pasteur"
 * merely because it is an exact string.
 *
 * Food intent (when the query names a catalog food, see {@link FoodIntent}):
 *   - T3 also takes restaurants with catalog evidence for that food (any
 *     synonym in the name, or the food on the FoodMap menu);
 *   - for an ASCII query, a name that matched only through a different
 *     accented word ("Cafe Phố" for pho → phở) drops to the bottom of T4;
 *   - T5 address-only matches are dropped.
 *
 * Inside a tier (or band): accent-exact before ASCII fallback → name starts
 * with the phrase before a later match (name tiers only) → rated before
 * unrated, higher rating, more reviews →
 * nearer first (only with a real user origin) → lower id. Rating and distance
 * are tie-breaks only; trigram similarity never orders results.
 */
public final class SearchRanker {

    public static final double FUZZY_MIN_SIMILARITY = 0.30;
    public static final int FUZZY_MIN_QUERY_CHARS = 4;

    /** One restaurant from stage 1. {@code nameSimilarity} is pg_trgm similarity on folded name/query. */
    public record Candidate(long id, String name, String address, BigDecimal avgRating, int reviewCount,
                            double latitude, double longitude, double nameSimilarity, List<String> dishNames) {}

    /** The user's real location; never a city-centre or map-centre fallback. */
    public record Origin(double lat, double lng) {}

    /**
     * A ranked hit with the evidence that placed it. {@code accentVariant}:
     * an ASCII food query matched this name only through a different accented
     * word; such hits sit at the bottom of T4.
     */
    public record Ranked(Candidate candidate, SearchTier tier, Quality quality, boolean atStart,
                         Double distanceMeters, boolean accentVariant) {}

    private SearchRanker() {}

    public static List<Ranked> rank(String query, List<Candidate> candidates, Origin origin) {
        return rank(query, candidates, origin, null);
    }

    /** @param intent the catalog food the query names, or null */
    public static List<Ranked> rank(String query, List<Candidate> candidates, Origin origin, FoodIntent intent) {
        List<String> q = VietnameseText.tokenize(query);
        if (q.isEmpty()) return List.of();
        boolean fuzzyAllowed = VietnameseText.foldedLengthWithoutSpaces(query) >= FUZZY_MIN_QUERY_CHARS;
        boolean asciiQuery = VietnameseText.isAscii(query);

        return candidates.stream()
            .map(c -> classify(q, fuzzyAllowed, c, origin, intent, asciiQuery))
            .flatMap(Optional::stream)
            .sorted(order(q.size() == 1, origin != null))
            .toList();
    }

    static Optional<Ranked> classify(List<String> q, boolean fuzzyAllowed, Candidate c, Origin origin,
                                     FoodIntent intent, boolean asciiQuery) {
        Double distance = origin == null ? null
            : GeoUtils.distanceMeters(origin.lat(), origin.lng(), c.latitude(), c.longitude());
        List<String> nameTokens = VietnameseText.tokenize(c.name());

        Optional<PhraseMatch> name = VietnameseText.findPhrase(q, nameTokens, false);
        PhraseMatch variant = null;
        if (name.isPresent()) {
            PhraseMatch m = name.get();
            if (intent != null && asciiQuery
                    && intent.isAccentVariant(nameTokens.subList(m.start(), m.start() + q.size()))) {
                variant = m;   // e.g. "Cafe Phố" for "pho": not phở — unless food evidence says otherwise
            } else {
                SearchTier tier = m.wholeText() ? SearchTier.EXACT_NAME : SearchTier.WHOLE_WORD_NAME;
                return Optional.of(new Ranked(c, tier, m.quality(), m.start() == 0, distance, false));
            }
        }

        // "starts with" is a name signal only; menu and address hits never use it
        PhraseMatch dish = bestDishMatch(q, c.dishNames());
        if (dish != null) {
            return Optional.of(new Ranked(c, SearchTier.FOOD_MENU, dish.quality(), false, distance, false));
        }
        if (intent != null && intent.hasEvidence(c)) {
            return Optional.of(new Ranked(c, SearchTier.FOOD_MENU, Quality.EXACT, false, distance, false));
        }
        if (variant != null) {
            return Optional.of(new Ranked(c, SearchTier.FUZZY_NAME, variant.quality(), variant.start() == 0,
                distance, true));
        }

        Optional<PhraseMatch> partial = VietnameseText.findPhrase(q, nameTokens, true);
        if (partial.isPresent()) {
            PhraseMatch m = partial.get();
            return Optional.of(new Ranked(c, SearchTier.FUZZY_NAME, m.quality(), m.start() == 0, distance, false));
        }
        if (fuzzyAllowed && c.nameSimilarity() >= FUZZY_MIN_SIMILARITY
                && !VietnameseText.hasConflict(q, nameTokens)
                && everyWordHasFuzzyCounterpart(q, nameTokens)) {
            return Optional.of(new Ranked(c, SearchTier.FUZZY_NAME, Quality.EXACT, false, distance, false));
        }

        if (intent != null) return Optional.empty();   // a food query never matches on address text
        Optional<PhraseMatch> address = VietnameseText.findPhrase(
            q, VietnameseText.tokenize(addressWithoutName(c.address(), c.name())), false);
        return address.map(m -> new Ranked(c, SearchTier.ADDRESS_ONLY, m.quality(), false, distance, false));
    }

    /**
     * Whole-name similarity can clear the threshold on one shared generic word
     * alone: "bún chả" ↔ "Bún Bò" is 0.40 from "bún", while "chả" has nothing
     * in common with "bò". For a multi-word query every word must itself be
     * trigram-similar (folded, same 0.30 threshold) to some name word.
     */
    static boolean everyWordHasFuzzyCounterpart(List<String> q, List<String> nameTokens) {
        if (q.size() < 2) return true;
        for (String qw : q) {
            Set<String> qt = wordTrigrams(qw);
            if (nameTokens.stream().noneMatch(nw -> similarity(qt, wordTrigrams(nw)) >= FUZZY_MIN_SIMILARITY)) {
                return false;
            }
        }
        return true;
    }

    /** pg_trgm's trigrams of one folded word: padded with two spaces before and one after. */
    private static Set<String> wordTrigrams(String word) {
        String p = "  " + VietnameseText.fold(word) + " ";
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= p.length(); i++) out.add(p.substring(i, i + 3));
        return out;
    }

    private static double similarity(Set<String> a, Set<String> b) {
        int shared = 0;
        for (String t : a) if (b.contains(t)) shared++;
        return (double) shared / (a.size() + b.size() - shared);
    }

    private static PhraseMatch bestDishMatch(List<String> q, List<String> dishNames) {
        PhraseMatch best = null;
        for (String dish : dishNames == null ? List.<String>of() : dishNames) {
            PhraseMatch m = VietnameseText.findPhrase(q, VietnameseText.tokenize(dish), false).orElse(null);
            if (m == null) continue;
            if (best == null || m.quality().compareTo(best.quality()) < 0
                    || (m.quality() == best.quality() && m.start() < best.start())) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Goong addresses usually repeat the place name first ("Phở, 187B …").
     * Without that prefix, a name word would count as an address match too.
     */
    static String addressWithoutName(String address, String name) {
        if (address == null) return "";
        if (name == null || name.isBlank()) return address;
        String a = Normalizer.normalize(address.strip(), Normalizer.Form.NFC);
        String n = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (a.length() > n.length() && a.regionMatches(true, 0, n, 0, n.length()) && a.charAt(n.length()) == ',') {
            return a.substring(n.length() + 1).strip();
        }
        return a;
    }

    /** Band index: T1..T5 → 0..4, except a one-word query puts T1 and T2 in the same band. */
    static int band(SearchTier tier, boolean singleToken) {
        if (singleToken && tier == SearchTier.WHOLE_WORD_NAME) return SearchTier.EXACT_NAME.ordinal();
        return tier.ordinal();
    }

    private static boolean rated(Candidate c) {
        return c.reviewCount() > 0 && c.avgRating() != null && c.avgRating().signum() > 0;
    }

    static Comparator<Ranked> order(boolean singleToken, boolean hasOrigin) {
        Comparator<Ranked> cmp = Comparator
            .comparingInt((Ranked r) -> band(r.tier(), singleToken))
            .thenComparing(Ranked::accentVariant)          // only T4 hits can be variants → bottom of T4
            .thenComparing(Ranked::quality)
            .thenComparing(r -> !r.atStart())
            .thenComparing(r -> !rated(r.candidate()))
            .thenComparing((Ranked r) -> rated(r.candidate()) ? r.candidate().avgRating() : BigDecimal.ZERO,
                           Comparator.reverseOrder())
            .thenComparing((Ranked r) -> r.candidate().reviewCount(), Comparator.reverseOrder());
        if (hasOrigin) {
            cmp = cmp.thenComparingDouble(Ranked::distanceMeters);
        }
        return cmp.thenComparingLong(r -> r.candidate().id());
    }
}
