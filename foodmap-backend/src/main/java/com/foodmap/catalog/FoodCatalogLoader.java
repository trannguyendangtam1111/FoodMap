package com.foodmap.catalog;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates {@code classpath:catalog/food-catalog.yml}.
 *
 * Any problem throws {@link InvalidFoodCatalogException} naming the entry and
 * the rule, so the application refuses to start with a broken vocabulary:
 * unknown fields, missing/invalid key, slug, label, kind, parentCategory,
 * tone, artKey, keywords; duplicate keys; duplicate discoverable order; the
 * same phrase claimed by two entries (ambiguous food intent).
 */
public final class FoodCatalogLoader {

    public static final String RESOURCE = "catalog/food-catalog.yml";

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    /** Reserved: "/decisions/foods/available" must never be mistaken for a food key. */
    private static final Set<String> RESERVED_KEYS = Set.of("available");
    private static final Set<String> FIELDS = Set.of("key", "label", "kind", "parentCategory", "tone",
        "keywords", "suppressors", "discoverable", "order", "artKey");

    private FoodCatalogLoader() {}

    public static FoodVocabulary loadFromClasspath() {
        try (InputStream in = FoodCatalogLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new InvalidFoodCatalogException("Food catalog not found on the classpath: " + RESOURCE);
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new InvalidFoodCatalogException("Food catalog could not be read: " + e.getMessage());
        }
    }

    public static FoodVocabulary parse(byte[] content) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(content, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw fail("the file is not valid YAML (" + e.getClass().getSimpleName() + ")");
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("foods") instanceof List<?> foods) || foods.isEmpty()) {
            throw fail("expected a non-empty 'foods' list at the top level");
        }

        List<FoodEntry> parsed = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Map<Integer, String> orders = new HashMap<>();
        for (int i = 0; i < foods.size(); i++) {
            FoodEntry e = parseEntry(foods.get(i), i);
            if (!keys.add(e.key())) throw fail(e.key(), "duplicate key");
            if (e.discoverable() && orders.putIfAbsent(e.order(), e.key()) != null) {
                throw fail(e.key(), "order " + e.order() + " is already used by '" + orders.get(e.order()) + "'");
            }
            parsed.add(e);
        }
        checkPhrasesAreUnambiguous(parsed);

        // Canonical order: discoverable by `order`, then classification-only in file order.
        List<FoodEntry> ordered = new ArrayList<>(parsed.stream().filter(FoodEntry::discoverable)
            .sorted(Comparator.comparingInt(FoodEntry::order)).toList());
        ordered.addAll(parsed.stream().filter(e -> !e.discoverable()).toList());
        List<FoodEntry> indexed = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            FoodEntry e = ordered.get(i);
            indexed.add(new FoodEntry(e.key(), e.label(), e.kind(), e.parentCategory(), e.tone(), e.keywords(),
                e.suppressors(), e.labelTerm(), e.discoverable(), e.order(), e.artKey(), i));
        }
        return new FoodVocabulary(indexed, sha256(content).substring(0, 16));
    }

    private static FoodEntry parseEntry(Object raw, int position) {
        if (!(raw instanceof Map<?, ?> m)) throw fail("entry #" + (position + 1) + " is not a mapping");
        String where = m.get("key") instanceof String k ? k : "entry #" + (position + 1);
        for (Object field : m.keySet()) {
            if (!FIELDS.contains(String.valueOf(field))) throw fail(where, "unknown field '" + field + "'");
        }

        String key = string(m, "key", where);
        if (!SLUG.matcher(key).matches()) throw fail(where, "key must be a lowercase slug (a-z, 0-9, '-')");
        if (RESERVED_KEYS.contains(key)) throw fail(where, "key '" + key + "' is reserved");

        String label = string(m, "label", where);

        FoodKind kind;
        try {
            kind = FoodKind.valueOf(string(m, "kind", where));
        } catch (IllegalArgumentException e) {
            throw fail(where, "kind must be one of MEAL, DRINK, DESSERT");
        }

        String categoryKey = string(m, "parentCategory", where);
        FoodCategory category = FoodCategory.fromKey(categoryKey)
            .orElseThrow(() -> fail(where, "unknown parentCategory '" + categoryKey + "'"));

        String tone = category.defaultTone();
        if (m.containsKey("tone")) {
            tone = string(m, "tone", where);
            if (!FoodCategory.TONES.contains(tone)) throw fail(where, "unknown tone '" + tone + "'");
        }

        List<FoodTerm> keywords = terms(m.get("keywords"), where, "keywords", true);
        if (keywords.isEmpty()) throw fail(where, "needs at least one keyword");
        List<FoodTerm> suppressors = m.containsKey("suppressors")
            ? terms(m.get("suppressors"), where, "suppressors", false) : List.of();

        if (!(m.get("discoverable") instanceof Boolean discoverable)) {
            throw fail(where, "discoverable must be true or false");
        }
        Integer order = null;
        if (m.containsKey("order")) {
            if (!(m.get("order") instanceof Integer o) || o <= 0) throw fail(where, "order must be a positive integer");
            order = o;
        }
        if (discoverable && order == null) throw fail(where, "a discoverable food needs an order");

        String artKey = key;
        if (m.containsKey("artKey")) {
            artKey = string(m, "artKey", where);
            if (!SLUG.matcher(artKey).matches()) throw fail(where, "artKey must be a lowercase slug");
        }

        // The label also resolves queries ("món chay"). Its folded form is
        // ASCII-safe unless a keyword with the same folded form is not.
        FoodTerm probe = FoodTerm.of(label, true);
        if (probe.tokens().isEmpty()) throw fail(where, "label has no words");
        boolean labelAsciiSafe = keywords.stream()
            .noneMatch(k -> !k.asciiSafe() && k.foldedTokens().equals(probe.foldedTokens()));
        FoodTerm labelTerm = FoodTerm.of(label, labelAsciiSafe);

        return new FoodEntry(key, label, kind, category, tone, keywords, suppressors, labelTerm,
            discoverable, order, artKey, position);
    }

    private static List<FoodTerm> terms(Object raw, String where, String field, boolean allowAsciiSafe) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw fail(where, field + " must be a list");
        List<FoodTerm> out = new ArrayList<>();
        for (Object item : list) {
            String text;
            boolean asciiSafe = true;
            if (item instanceof String s) {
                text = s;
            } else if (allowAsciiSafe && item instanceof Map<?, ?> km) {
                for (Object f : km.keySet()) {
                    if (!Set.of("text", "asciiSafe").contains(String.valueOf(f))) {
                        throw fail(where, field + " entries allow only 'text' and 'asciiSafe'");
                    }
                }
                if (!(km.get("text") instanceof String s)) throw fail(where, field + " entry needs a 'text'");
                text = s;
                if (km.containsKey("asciiSafe")) {
                    if (!(km.get("asciiSafe") instanceof Boolean b)) throw fail(where, "asciiSafe must be true or false");
                    asciiSafe = b;
                }
            } else {
                throw fail(where, field + " entries must be text");
            }
            FoodTerm term = FoodTerm.of(text, asciiSafe);
            if (term.tokens().isEmpty()) throw fail(where, field + " contains an entry with no words");
            out.add(term);
        }
        return List.copyOf(out);
    }

    /**
     * A phrase (keyword or label) must point to one food only — both as
     * written and, when ASCII-safe, in its folded form. Otherwise a query
     * like "pho" could resolve to two foods.
     */
    private static void checkPhrasesAreUnambiguous(List<FoodEntry> entries) {
        Map<List<String>, String> exact = new HashMap<>();
        Map<List<String>, String> folded = new HashMap<>();
        for (FoodEntry e : entries) {
            List<FoodTerm> phrases = new ArrayList<>(e.keywords());
            phrases.add(e.labelTerm());
            for (FoodTerm t : phrases) {
                String other = exact.putIfAbsent(t.tokens(), e.key());
                if (other != null && !other.equals(e.key())) {
                    throw fail(e.key(), "phrase '" + t.text() + "' is also used by '" + other + "'");
                }
                if (t.asciiSafe()) {
                    other = folded.putIfAbsent(t.foldedTokens(), e.key());
                    if (other != null && !other.equals(e.key())) {
                        throw fail(e.key(), "phrase '" + t.text() + "' folds to the same text as a phrase of '" + other + "'");
                    }
                }
            }
        }
    }

    private static String string(Map<?, ?> m, String field, String where) {
        if (!(m.get(field) instanceof String s) || s.isBlank()) throw fail(where, "'" + field + "' is required");
        return s.strip();
    }

    private static InvalidFoodCatalogException fail(String message) {
        return new InvalidFoodCatalogException("Invalid food catalog (" + RESOURCE + "): " + message);
    }

    private static InvalidFoodCatalogException fail(String entry, String message) {
        return fail("food '" + entry + "': " + message);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
