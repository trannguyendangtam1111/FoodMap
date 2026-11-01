package com.foodmap.food.match;

import com.foodmap.catalog.FoodCategory;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodTerm;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.text.VietnameseText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Text → food, using only the catalog and the text itself (no I/O).
 *
 * Evidence rules (Master Specification §6.3), on {@link VietnameseText} tokens:
 *   - whole words / phrases only — never fuzzy, never a substring;
 *   - a text with Vietnamese diacritics matches only the accented keyword
 *     ("Cafe Phố" is not phở, "Lầu 4" is not lẩu, "chạy" is not chay);
 *   - a text written without diacritics ("Viet Pho Restaurant") matches the
 *     folded keyword, but only when the keyword is ASCII-safe;
 *   - a suppressor in a text (name or menu dish) cancels that entry's match
 *     in that text ("Bánh Mì Thịt Nướng" is not mì, "Restaurant & Cafe" is
 *     not a café);
 *   - name evidence beats menu evidence.
 *
 * Query resolution ("does this search mean a food?") compares the whole query
 * with a keyword or the label, token by token: an accented query token must
 * match exactly, an ASCII one matches the folded form of an ASCII-safe phrase.
 */
public final class FoodMatcher {

    /** Menu-only category needs ≥ 2 dishes and one category on ≥ 60 % of them (V1 inferCategory rule). */
    static final int MENU_MIN_DISHES = 2;
    static final double MENU_MAJORITY = 0.6;

    private final FoodVocabulary vocabulary;

    public FoodMatcher(FoodVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    public FoodVocabulary vocabulary() {
        return vocabulary;
    }

    // ── Evidence ─────────────────────────────────────────────────────────────

    /** A keyword phrase of {@code entry} in a name (respecting suppressors). Also used for dish names. */
    public boolean nameMatches(FoodEntry entry, String name) {
        return nameHit(entry, Text.of(name)).isPresent();
    }

    /** The first menu dish that names {@code entry}, if any. */
    public Optional<String> menuMatch(FoodEntry entry, List<String> dishNames) {
        if (dishNames == null) return Optional.empty();
        for (String dish : dishNames) {
            if (nameHit(entry, Text.of(dish)).isPresent()) return Optional.of(dish);
        }
        return Optional.empty();
    }

    public FoodClassification classify(String name, String cuisineType, List<String> dishNames) {
        List<String> dishes = dishNames == null ? List.of() : dishNames;
        Text nameText = Text.of(name);

        List<NameHit> hits = new ArrayList<>();
        for (FoodEntry e : vocabulary.entries()) {
            nameHit(e, nameText).ifPresent(hits::add);
        }
        hits.sort(Comparator.comparingInt(NameHit::position)
            .thenComparing(Comparator.comparingInt(NameHit::tokenCount).reversed())
            .thenComparing(Comparator.comparingInt(NameHit::charCount).reversed())
            .thenComparingInt(h -> h.entry().index()));

        Map<String, FoodEvidence> evidence = new LinkedHashMap<>();
        for (NameHit h : hits) evidence.put(h.entry().key(), FoodEvidence.name());
        for (FoodEntry e : vocabulary.entries()) {
            if (evidence.containsKey(e.key())) continue;
            menuMatch(e, dishes).ifPresent(d -> evidence.put(e.key(), FoodEvidence.menu(d)));
        }

        String category = !hits.isEmpty()
            ? hits.get(0).entry().parentCategory().key()
            : menuCategory(dishes);
        if (category == null && mentionsVietnamese(cuisineType)) category = FoodCategory.VIETNAMESE.key();

        return evidence.isEmpty() && category == null
            ? FoodClassification.NONE
            : new FoodClassification(List.copyOf(evidence.keySet()), category, Map.copyOf(evidence));
    }

    /**
     * Category from the menu alone: each dish counts for the entry whose
     * keyword appears earliest in it; one category must cover ≥ 60 % of at
     * least two dishes. Otherwise unknown — a mixed menu proves nothing.
     */
    private String menuCategory(List<String> dishes) {
        if (dishes.size() < MENU_MIN_DISHES) return null;
        Map<FoodCategory, Integer> counts = new HashMap<>();
        for (String dish : dishes) {
            Text t = Text.of(dish);
            vocabulary.entries().stream()
                .map(e -> nameHit(e, t))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(NameHit::position)
                    .thenComparing(Comparator.comparingInt(NameHit::tokenCount).reversed())
                    .thenComparingInt(h -> h.entry().index()))
                .ifPresent(h -> counts.merge(h.entry().parentCategory(), 1, Integer::sum));
        }
        return counts.entrySet().stream()
            .filter(c -> c.getValue() >= MENU_MAJORITY * dishes.size())
            .max(Map.Entry.comparingByValue())
            .map(c -> c.getKey().key())
            .orElse(null);
    }

    private static boolean mentionsVietnamese(String cuisineType) {
        if (cuisineType == null) return false;
        List<String> tokens = VietnameseText.tokenize(VietnameseText.fold(cuisineType));
        return tokens.contains("vietnam") || tokens.contains("vietnamese") || tokens.contains("viet");
    }

    // ── Query intent ─────────────────────────────────────────────────────────

    /**
     * The food a whole query names — a keyword or the label, never a fuzzy
     * guess. Empty when nothing (or, defensively, more than one food) matches.
     */
    public Optional<FoodEntry> resolveQuery(String query) {
        List<String> q = VietnameseText.tokenize(query);
        if (q.isEmpty()) return Optional.empty();
        List<FoodEntry> found = vocabulary.entries().stream()
            .filter(e -> phrases(e).stream().anyMatch(t -> queryEquals(q, t)))
            .toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * Discoverable foods for a search box: the food the query names first,
     * then foods whose phrase the query is typing (last word may be partial),
     * then foods named inside a longer query ("phở bò tái" → phở).
     */
    public List<FoodEntry> matchQuery(String query, int limit) {
        List<String> q = VietnameseText.tokenize(query);
        if (q.isEmpty()) return List.of();
        record Scored(FoodEntry entry, int rank) {}
        return vocabulary.discoverable().stream()
            .map(e -> {
                List<FoodTerm> terms = phrases(e);
                if (terms.stream().anyMatch(t -> queryEquals(q, t))) return new Scored(e, 0);
                if (terms.stream().anyMatch(t -> queryStarts(q, t))) return new Scored(e, 1);
                if (terms.stream().anyMatch(t -> queryContains(q, t))) return new Scored(e, 2);
                return null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(Scored::rank).thenComparingInt(s -> s.entry().index()))
            .limit(limit)
            .map(Scored::entry)
            .toList();
    }

    /**
     * For an ASCII query resolved to {@code entry}: the matched name tokens are
     * a different accented word ("phố" for pho → phở), not this food.
     */
    public boolean isAccentVariant(FoodEntry entry, List<String> matchedTokens) {
        if (matchedTokens.stream().allMatch(VietnameseText::isAscii)) return false;
        return phrases(entry).stream().noneMatch(t -> t.tokens().equals(matchedTokens));
    }

    private static List<FoodTerm> phrases(FoodEntry e) {
        List<FoodTerm> all = new ArrayList<>(e.keywords());
        all.add(e.labelTerm());
        return all;
    }

    private static boolean tokenEquals(String q, String termToken, String foldedTermToken, boolean asciiSafe) {
        return VietnameseText.isAscii(q) ? asciiSafe && q.equals(foldedTermToken) : q.equals(termToken);
    }

    private static boolean queryEquals(List<String> q, FoodTerm t) {
        if (q.size() != t.tokens().size()) return false;
        for (int i = 0; i < q.size(); i++) {
            if (!tokenEquals(q.get(i), t.tokens().get(i), t.foldedTokens().get(i), t.asciiSafe())) return false;
        }
        return true;
    }

    private static boolean queryStarts(List<String> q, FoodTerm t) {
        if (q.size() > t.tokens().size()) return false;
        int last = q.size() - 1;
        for (int i = 0; i < last; i++) {
            if (!tokenEquals(q.get(i), t.tokens().get(i), t.foldedTokens().get(i), t.asciiSafe())) return false;
        }
        String ql = q.get(last);
        return VietnameseText.isAscii(ql)
            ? t.asciiSafe() && t.foldedTokens().get(last).startsWith(ql)
            : t.tokens().get(last).startsWith(ql);
    }

    private static boolean queryContains(List<String> q, FoodTerm t) {
        int n = t.tokens().size();
        for (int start = 0; start + n <= q.size(); start++) {
            if (queryEquals(q.subList(start, start + n), t)) return true;
        }
        return false;
    }

    // ── Phrase search on a text ──────────────────────────────────────────────

    private record Text(List<String> tokens, boolean ascii) {
        static Text of(String s) {
            return new Text(VietnameseText.tokenize(s), s == null || VietnameseText.isAscii(s));
        }
    }

    private record NameHit(FoodEntry entry, int position, int tokenCount, int charCount) {}

    private Optional<NameHit> nameHit(FoodEntry e, Text text) {
        if (earliest(e.suppressors(), text) != null) return Optional.empty();
        return nameHitIgnoringSuppressors(e, text);
    }

    private static Optional<NameHit> nameHitIgnoringSuppressors(FoodEntry e, Text text) {
        int[] best = null;   // {position, tokenCount, charCount}
        for (FoodTerm k : e.keywords()) {
            int pos = indexOf(text, k);
            if (pos < 0) continue;
            int[] hit = {pos, k.tokens().size(), k.text().length()};
            if (best == null || hit[0] < best[0] || (hit[0] == best[0]
                    && (hit[1] > best[1] || (hit[1] == best[1] && hit[2] > best[2])))) {
                best = hit;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new NameHit(e, best[0], best[1], best[2]));
    }

    /** Earliest position of any of {@code terms} in the text, or null. */
    private static Integer earliest(List<FoodTerm> terms, Text text) {
        Integer best = null;
        for (FoodTerm t : terms) {
            int pos = indexOf(text, t);
            if (pos >= 0 && (best == null || pos < best)) best = pos;
        }
        return best;
    }

    /** Whole-word phrase position: accented text ↔ accented tokens; ASCII text ↔ folded tokens if ASCII-safe. */
    private static int indexOf(Text text, FoodTerm term) {
        List<String> phrase;
        if (text.ascii()) {
            if (!term.asciiSafe()) return -1;
            phrase = term.foldedTokens();
        } else {
            phrase = term.tokens();
        }
        int n = phrase.size();
        for (int start = 0; start + n <= text.tokens().size(); start++) {
            if (text.tokens().subList(start, start + n).equals(phrase)) return start;
        }
        return -1;
    }
}
