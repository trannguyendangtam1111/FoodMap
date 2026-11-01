package com.foodmap.common.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Accent-aware Vietnamese text matching, shared by search (and, later, the
 * food vocabulary). Pure functions, no I/O.
 *
 * Vietnamese tones change meaning — "phở" (noodle soup) and "phố" (street),
 * "lẩu" (hotpot) and "lầu" (floor) — so diacritics are respected whenever the
 * query has them:
 *
 *   accented query token  matches  the same accented token (EXACT), or an
 *                                  ASCII-written token equal to its folded
 *                                  form, e.g. "pho" in "Viet Pho" (ASCII_FALLBACK);
 *                         never    a differently accented token ("phố").
 *   ASCII query token     matches  any token whose folded form equals it
 *                                  ("pho" → phở, phố, Pho …) — the user gave
 *                                  no accents, so there is nothing to respect.
 *
 * normalize: NFC, Vietnamese lowercase, whitespace collapsed, trimmed.
 * fold:      normalize, then strip combining marks and map đ → d.
 * tokens:    maximal runs of Unicode letters, digits and combining marks.
 */
public final class VietnameseText {

    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKEN_SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}\\p{M}]+");

    /** How a query token matched a text token; EXACT ranks above ASCII_FALLBACK. */
    public enum Quality { EXACT, ASCII_FALLBACK }

    /**
     * A query phrase found in a text.
     *
     * @param start      index of the first matched text token
     * @param quality    the weakest token match in the phrase
     * @param wholeText  the phrase covers every token of the text
     * @param usedPrefix the last query token matched only as a word prefix
     */
    public record PhraseMatch(int start, Quality quality, boolean wholeText, boolean usedPrefix) {}

    private VietnameseText() {}

    public static String normalize(String s) {
        if (s == null) return "";
        String lower = Normalizer.normalize(s, Normalizer.Form.NFD).toLowerCase(VI);
        String canonical = TONE_ON_U.matcher(TONE_ON_O.matcher(lower).replaceAll("$1$3$2")).replaceAll("$1$3$2");
        String nfc = Normalizer.normalize(canonical, Normalizer.Form.NFC);
        return WHITESPACE.matcher(nfc).replaceAll(" ").trim();
    }

    // Vietnamese has two tone-placement conventions for oa / oe / uy:
    // "hòa" vs "hoà", "hòe" vs "hoè", "thùy" vs "thuỳ". Keyboards and data
    // mix them, so both are canonicalised (on NFD text) to the tone on the
    // second vowel. Only a plain o/u carrying exactly one tone mark directly
    // before a plain a/e/y is moved — "của", "mùa", "hoàng" are untouched.
    private static final String TONE = "[\\u0300\\u0301\\u0303\\u0309\\u0323]";
    private static final Pattern TONE_ON_O = Pattern.compile("(o)(" + TONE + ")([ae])(?![\\u0302\\u0306\\u031B])");
    private static final Pattern TONE_ON_U = Pattern.compile("(u)(" + TONE + ")(y)");

    public static String fold(String s) {
        String decomposed = Normalizer.normalize(normalize(s), Normalizer.Form.NFD);
        String stripped = MARKS.matcher(decomposed).replaceAll("").replace('đ', 'd');
        return Normalizer.normalize(stripped, Normalizer.Form.NFC);
    }

    public static List<String> tokenize(String s) {
        String n = normalize(s);
        if (n.isEmpty()) return List.of();
        return Arrays.stream(TOKEN_SEPARATORS.split(n)).filter(t -> !t.isEmpty()).toList();
    }

    /** True when folding changes nothing, i.e. the token carries no Vietnamese diacritics. */
    public static boolean isAscii(String token) {
        return fold(token).equals(normalize(token));
    }

    /** Whole-token match of one query token against one text token. */
    public static Optional<Quality> matchToken(String query, String text) {
        query = normalize(query);
        text = normalize(text);
        if (!isAscii(query)) {
            if (text.equals(query)) return Optional.of(Quality.EXACT);
            if (isAscii(text) && text.equals(fold(query))) return Optional.of(Quality.ASCII_FALLBACK);
            return Optional.empty();
        }
        return fold(text).equals(query) ? Optional.of(Quality.EXACT) : Optional.empty();
    }

    /**
     * Like {@link #matchToken} but the query token only has to start the text
     * token. An accented query token must keep its accents here: the ASCII
     * fallback assumes a whole word written without diacritics, and as a mere
     * prefix it would let "phở" start "Phong".
     */
    public static Optional<Quality> matchTokenPrefix(String query, String text) {
        query = normalize(query);
        text = normalize(text);
        if (!isAscii(query)) {
            return text.startsWith(query) ? Optional.of(Quality.EXACT) : Optional.empty();
        }
        return fold(text).startsWith(query) ? Optional.of(Quality.EXACT) : Optional.empty();
    }

    /**
     * An accented query token and a differently accented text token with the
     * same letters ("phở" vs "phố"). Such a pair never counts as a match.
     */
    public static boolean conflicts(String query, String text) {
        query = normalize(query);
        text = normalize(text);
        return !isAscii(query) && !isAscii(text) && !text.equals(query) && fold(text).equals(fold(query));
    }

    public static boolean hasConflict(List<String> queryTokens, List<String> textTokens) {
        for (String q : queryTokens) {
            for (String t : textTokens) {
                if (conflicts(q, t)) return true;
            }
        }
        return false;
    }

    /**
     * Finds the query tokens as consecutive text tokens. With
     * {@code allowPartialLast}, the last query token may match as a prefix
     * of its text token (search-as-you-type: "trà s" → "trà sữa").
     * Best match wins: EXACT over ASCII_FALLBACK, then the earliest position.
     */
    public static Optional<PhraseMatch> findPhrase(List<String> queryTokens, List<String> textTokens,
                                                   boolean allowPartialLast) {
        int q = queryTokens.size();
        if (q == 0 || q > textTokens.size()) return Optional.empty();

        PhraseMatch best = null;
        for (int start = 0; start + q <= textTokens.size(); start++) {
            PhraseMatch m = matchAt(queryTokens, textTokens, start, allowPartialLast);
            if (m == null) continue;
            if (best == null || m.quality().compareTo(best.quality()) < 0) best = m;
            if (best.quality() == Quality.EXACT) break;   // earliest EXACT cannot be beaten
        }
        return Optional.ofNullable(best);
    }

    private static PhraseMatch matchAt(List<String> query, List<String> text, int start, boolean allowPartialLast) {
        Quality worst = Quality.EXACT;
        boolean usedPrefix = false;
        for (int i = 0; i < query.size(); i++) {
            String qt = query.get(i);
            String tt = text.get(start + i);
            Optional<Quality> m = matchToken(qt, tt);
            if (m.isEmpty() && allowPartialLast && i == query.size() - 1) {
                m = matchTokenPrefix(qt, tt);
                usedPrefix = m.isPresent();
            }
            if (m.isEmpty()) return null;
            if (m.get().compareTo(worst) > 0) worst = m.get();
        }
        boolean whole = start == 0 && query.size() == text.size();
        return new PhraseMatch(start, worst, whole, usedPrefix);
    }

    /** Length of the folded text without whitespace — the fuzzy-match length gate. */
    public static int foldedLengthWithoutSpaces(String s) {
        return fold(s).replace(" ", "").length();
    }
}
