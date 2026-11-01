package com.foodmap.common.text;

import com.foodmap.common.text.VietnameseText.PhraseMatch;
import com.foodmap.common.text.VietnameseText.Quality;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;

import static com.foodmap.common.text.VietnameseText.findPhrase;
import static com.foodmap.common.text.VietnameseText.tokenize;
import static org.assertj.core.api.Assertions.assertThat;

class VietnameseTextTest {

    private static Optional<PhraseMatch> phrase(String query, String text) {
        return findPhrase(tokenize(query), tokenize(text), false);
    }

    // ── Normalisation ────────────────────────────────────────────────────────

    @Test
    void normalize_nfcLowercaseAndWhitespace() {
        String decomposed = Normalizer.normalize("PHỞ   Hòa\tPasteur ", Normalizer.Form.NFD);
        // "hòa" is stored in the canonical tone placement "hoà" (see the next test)
        assertThat(VietnameseText.normalize(decomposed)).isEqualTo("phở hoà pasteur");
        assertThat(VietnameseText.normalize(null)).isEmpty();
    }

    @Test
    void normalize_canonicalisesOldAndNewTonePlacement() {
        assertThat(VietnameseText.normalize("Phở Hoà")).isEqualTo(VietnameseText.normalize("Phở Hòa"));
        assertThat(VietnameseText.normalize("hoè")).isEqualTo(VietnameseText.normalize("hòe"));
        assertThat(VietnameseText.normalize("Thuỳ")).isEqualTo(VietnameseText.normalize("Thùy"));
        assertThat(VietnameseText.conflicts("hoà", "hòa")).isFalse();
        // words whose tone is not on an oa/oe/uy pair are left alone
        assertThat(VietnameseText.normalize("của mùa hoàng")).isEqualTo("của mùa hoàng");
        assertThat(VietnameseText.matchToken("hòa", "hoà")).contains(Quality.EXACT);
    }

    @Test
    void fold_stripsMarksAndMapsD() {
        assertThat(VietnameseText.fold("Phở Đà Lạt")).isEqualTo("pho da lat");
        assertThat(VietnameseText.fold("Trà Sữa")).isEqualTo("tra sua");
        assertThat(VietnameseText.foldedLengthWithoutSpaces("trà s")).isEqualTo(4);
    }

    @Test
    void tokenize_onUnicodeLettersAndDigits() {
        assertThat(tokenize("Bún Đậu Ngõ Nhỏ, 156C Pasteur")).containsExactly("bún", "đậu", "ngõ", "nhỏ", "156c", "pasteur");
        assertThat(tokenize("Restaurant & Cafe 4221")).containsExactly("restaurant", "cafe", "4221");
        assertThat(tokenize("Trà Sữa #")).containsExactly("trà", "sữa");
        // a decomposed (NFD) accent stays inside its token
        assertThat(tokenize(Normalizer.normalize("Phở Gà", Normalizer.Form.NFD))).containsExactly("phở", "gà");
        assertThat(tokenize("  !!  ")).isEmpty();
    }

    @Test
    void isAscii_detectsDiacritics() {
        assertThat(VietnameseText.isAscii("pho")).isTrue();
        assertThat(VietnameseText.isAscii("cafe")).isTrue();
        assertThat(VietnameseText.isAscii("phở")).isFalse();
        assertThat(VietnameseText.isAscii("đậu")).isFalse();
    }

    // ── Token matching ───────────────────────────────────────────────────────

    @Test
    void accentedQuery_matchesSameAccentsExactly() {
        assertThat(VietnameseText.matchToken("phở", "phở")).contains(Quality.EXACT);
    }

    @Test
    void accentedQuery_neverMatchesADifferentlyAccentedToken() {
        for (String other : List.of("phố", "phổ", "phó")) {
            assertThat(VietnameseText.matchToken("phở", other)).as(other).isEmpty();
            assertThat(VietnameseText.matchTokenPrefix("phở", other)).as(other).isEmpty();
            assertThat(VietnameseText.conflicts("phở", other)).as(other).isTrue();
        }
        assertThat(VietnameseText.matchToken("lẩu", "lầu")).isEmpty();
        assertThat(VietnameseText.conflicts("lẩu", "lầu")).isTrue();
    }

    @Test
    void accentedQuery_fallsBackToAsciiWrittenTokens() {
        assertThat(VietnameseText.matchToken("phở", "pho")).contains(Quality.ASCII_FALLBACK);
        assertThat(VietnameseText.conflicts("phở", "pho")).isFalse();
    }

    @Test
    void asciiQuery_matchesEveryFoldedVariant() {
        for (String text : List.of("pho", "phở", "phố", "phổ")) {
            assertThat(VietnameseText.matchToken("pho", text)).as(text).contains(Quality.EXACT);
        }
        assertThat(VietnameseText.conflicts("pho", "phố")).isFalse();   // ASCII queries have no conflicts
        assertThat(VietnameseText.matchToken("cafe", "café")).contains(Quality.EXACT);
    }

    // ── Phrases ──────────────────────────────────────────────────────────────

    @Test
    void phrase_isWholeWordsAndConsecutive() {
        assertThat(phrase("trà sữa", "Trà sữa Koi")).get()
            .extracting(PhraseMatch::start, PhraseMatch::wholeText).containsExactly(0, false);
        assertThat(phrase("trà sữa", "Trà sữa")).get().extracting(PhraseMatch::wholeText).isEqualTo(true);
        assertThat(phrase("trà sữa", "Cafe Song Trà")).isEmpty();          // only "trà"
        assertThat(phrase("trà sữa", "Sữa Chua Nếp Cẩm")).isEmpty();       // only "sữa" (text-level case)
        assertThat(phrase("trà sữa", "Sữa Trà")).isEmpty();                // not consecutive in order
        assertThat(phrase("phở", "Phởx")).isEmpty();                       // not a whole word
    }

    @Test
    void phrase_prefersAccentExactThenEarliest() {
        PhraseMatch m = phrase("phở", "Pho Phở").orElseThrow();
        assertThat(m.start()).isEqualTo(1);
        assertThat(m.quality()).isEqualTo(Quality.EXACT);
        assertThat(phrase("phở", "Viet Pho Restaurant")).get()
            .extracting(PhraseMatch::quality).isEqualTo(Quality.ASCII_FALLBACK);
    }

    @Test
    void partialFinalToken_onlyWhenAllowed() {
        List<String> q = tokenize("trà s");
        List<String> text = tokenize("Trà sữa Min");
        assertThat(findPhrase(q, text, false)).isEmpty();
        assertThat(findPhrase(q, text, true)).get()
            .extracting(PhraseMatch::usedPrefix, PhraseMatch::start).containsExactly(true, 0);
        // only the LAST token may be partial
        assertThat(findPhrase(tokenize("tr sữa"), text, true)).isEmpty();
        // the prefix still respects accents, and an accented token never prefix-matches via ASCII
        assertThat(findPhrase(tokenize("phở"), tokenize("Phố Xưa"), true)).isEmpty();
        assertThat(VietnameseText.matchTokenPrefix("phở", "phong")).isEmpty();
        assertThat(VietnameseText.matchTokenPrefix("pho", "phong")).contains(Quality.EXACT);
    }

    @Test
    void hasConflict_acrossTokenLists() {
        assertThat(VietnameseText.hasConflict(tokenize("phở"), tokenize("Cafe Phố"))).isTrue();
        assertThat(VietnameseText.hasConflict(tokenize("phở"), tokenize("Phở Hòa Pasteur"))).isFalse();
        assertThat(VietnameseText.hasConflict(tokenize("pho"), tokenize("Cafe Phố"))).isFalse();
    }
}
