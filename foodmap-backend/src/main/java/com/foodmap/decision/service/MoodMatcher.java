package com.foodmap.decision.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a restaurant fits a {@link Mood}, using only facts we hold:
 * its name and the names of dishes on its FoodMap menu. No classification
 * model, no guessing — a restaurant with no matching word simply doesn't match.
 *
 * Matching rules (shared with the frontend's inferCategory):
 *  - whole words only, Unicode-aware ("bún bò" matches, "bánh mì" never matches "mì");
 *  - text with Vietnamese diacritics is matched against the accented keyword
 *    only, so "Phố" (street) is never read as "phở";
 *  - text typed without diacritics ("Pho 24") is matched against the stripped
 *    keyword — but only for keywords whose stripped form is unambiguous.
 */
public final class MoodMatcher {

    /** Where the evidence came from — drives the factual reason text. */
    public enum Source { NAME, MENU }

    public record Evidence(Source source, String dishName) {}

    private record Keyword(String accented, boolean asciiSafe) {}

    // Món nước: noodle soups. Deliberately excludes dry dishes that share a
    // head word (bún thịt nướng, bún đậu, mì xào, bánh mì) and lẩu / cháo,
    // which the product treats as their own categories.
    private static final List<Keyword> MON_NUOC = List.of(
        new Keyword("phở", true),
        new Keyword("bún bò", true),
        new Keyword("bún riêu", true),
        new Keyword("bún mắm", true),
        new Keyword("bún cá", true),
        new Keyword("bún mọc", true),
        new Keyword("bún măng", true),
        new Keyword("hủ tiếu", true),
        new Keyword("hủ tíu", true),
        new Keyword("hủ híu", true),
        new Keyword("bánh canh", true),
        new Keyword("mì quảng", true),
        new Keyword("miến", false)   // "mien" also reads as "miền" (region)
    );

    private MoodMatcher() {}

    /**
     * Evidence that the restaurant fits the mood, preferring its name over
     * its menu; empty when neither mentions a matching dish.
     */
    public static Optional<Evidence> evidence(Mood mood, String restaurantName, List<String> dishNames) {
        List<Keyword> keywords = switch (mood) {
            case MON_NUOC -> MON_NUOC;
        };
        if (matches(restaurantName, keywords)) {
            return Optional.of(new Evidence(Source.NAME, null));
        }
        for (String dish : dishNames) {
            if (matches(dish, keywords)) return Optional.of(new Evidence(Source.MENU, dish));
        }
        return Optional.empty();
    }

    static boolean matches(String rawText, List<Keyword> keywords) {
        if (rawText == null || rawText.isBlank()) return false;
        String text = Normalizer.normalize(rawText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean hasDiacritics = !stripDiacritics(text).equals(text);
        for (Keyword k : keywords) {
            if (hasDiacritics) {
                if (containsWord(text, k.accented())) return true;
            } else if (k.asciiSafe() && containsWord(text, stripDiacritics(k.accented()))) {
                return true;
            }
        }
        return false;
    }

    static String stripDiacritics(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('đ', 'd').replace('Đ', 'D');
    }

    private static boolean containsWord(String text, String word) {
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])");
        return p.matcher(text).find();
    }
}
