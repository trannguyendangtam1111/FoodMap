package com.foodmap.food.match;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.common.text.VietnameseText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FoodMatcher with the real catalog, on real restaurant names and FoodMap
 * menus from the dev database (snapshot 2026-10-03). Where no real row
 * exists for a rule, the rule is checked on plain text, never on an invented
 * restaurant.
 */
class FoodMatcherTest {

    private static final FoodMatcher M = new FoodMatcher(FoodCatalogLoader.loadFromClasspath());

    private static List<String> keys(String name) {
        return M.classify(name, null, List.of()).foodKeys();
    }

    private static Optional<String> resolved(String query) {
        return M.resolveQuery(query).map(FoodEntry::key);
    }

    // ── Name evidence ────────────────────────────────────────────────────────

    @Test
    void vietnameseExactMatch() {
        assertThat(keys("Phở Hòa Pasteur")).containsExactly("pho");
        assertThat(keys("Bánh Mì Huỳnh Hoa")).containsExactly("banh-mi");
        assertThat(keys("Cơm Tấm Mộc")).containsExactly("com");
        assertThat(keys("Lẩu Mắm Ngọc Giàu")).containsExactly("lau");
    }

    @Test
    void asciiWrittenNames_matchAsciiSafeKeywords() {
        assertThat(keys("Viet Pho Restaurant")).containsExactly("pho");
        assertThat(keys("Pizza Hut")).containsExactly("pizza");
        assertThat(keys("Sushi Tei")).containsExactly("sushi");
    }

    @Test
    void aliases_reachTheSameFood() {
        assertThat(keys("The Sweet Coffee")).containsExactly("ca-phe");
        assertThat(keys("Cafe Mộc")).containsExactly("ca-phe");
        assertThat(keys("Café Hà Đô")).containsExactly("ca-phe");
        assertThat(keys("Yeebo Hotpot And Seafoods Restaurant")).containsExactly("lau", "hai-san");
    }

    @Test
    void accentVariants_neverMatch() {
        assertThat(keys("Cafe Phố")).doesNotContain("pho").containsExactly("ca-phe");
        assertThat(keys("Bún Đậu Ngõ Nhỏ Phố Nhỏ")).containsExactly("bun");
        assertThat(keys("Cafe Thành Phố Mới")).doesNotContain("pho");
        // no restaurant name contains "Lầu"; the rule on plain text:
        assertThat(keys("Lầu 4")).isEmpty();
        assertThat(keys("Chạy bộ")).isEmpty();                  // "chạy" is not chay
        // old/new tone placement is the same word
        assertThat(keys("Phở Hoà")).containsExactly("pho");
    }

    @Test
    void suppressors_cancelNameEvidence() {
        assertThat(keys("Restaurant & Cafe 4221")).isEmpty();
        assertThat(keys("Restaurant and Coffee Phú Thành Hotel")).isEmpty();
        assertThat(keys("Anna Villa Coffee & Restaurant")).isEmpty();
        // a suppressor cancels only its own food
        assertThat(keys("Go Gelato (Restaurant - Bakery & Coffee)")).containsExactly("kem", "bakery");
        // "bánh mì" is bread, not mì (noodles) — in a name and in a menu dish
        assertThat(keys("Bánh Mì Huỳnh Hoa")).containsExactly("banh-mi").doesNotContain("mi");
        assertThat(M.classify("Bánh Mì Huỳnh Hoa", "Vietnamese",
            List.of("Bánh Mì Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt")).foodKeys()).containsExactly("banh-mi", "nuong");
        // "bánh kem" is cake, not kem (ice cream) — text-level, no such restaurant today
        assertThat(keys("Bánh Kem Hạnh Phúc")).isEmpty();
    }

    @Test
    void phrasesNotWords() {
        assertThat(keys("Trà sữa Koi")).containsExactly("tra-sua", "tra");    // longer phrase first
        assertThat(keys("Cafe Song Trà")).containsExactly("ca-phe", "tra").doesNotContain("tra-sua");
        assertThat(keys("Bún Bò")).containsExactly("bun-bo", "bun");
    }

    @Test
    void unrelatedWordsAreRejected_noFuzzyGuessing() {
        assertThat(keys("Phong Lan Restaurant (Sasco)")).isEmpty();
        assertThat(keys("Cửa hàng Điện Thoại Di Động Huy Phong")).isEmpty();
        assertThat(keys("Restaurant 1 26")).isEmpty();
        assertThat(keys("Long Ky Restaurant")).isEmpty();
        assertThat(keys("Phởx")).isEmpty();   // not a whole word (text-level)
    }

    @Test
    void asciiUnsafeKeywords_needDiacritics() {
        // ASCII "mi" / "chao" are ambiguous ("chào" — "Xin Chao"); text-level
        assertThat(keys("Mi Quang 1A")).isEmpty();
        assertThat(keys("Xin Chao Restaurant")).isEmpty();
        assertThat(keys("Mì Quảng 1A")).containsExactly("mi");
        assertThat(keys("Cháo Lòng Thanh Xuân")).containsExactly("chao");
    }

    // ── Classification ───────────────────────────────────────────────────────

    @Test
    void nameEvidence() {
        FoodClassification c = M.classify("Phở Hòa Pasteur", "Vietnamese",
            List.of("Gỏi Cuốn Tôm Thịt", "Phở Bò Tái", "Phở Gà"));
        assertThat(c.foodKeys()).containsExactly("pho");
        assertThat(c.evidence().get("pho")).isEqualTo(FoodEvidence.name());   // name beats menu
        assertThat(c.category()).isEqualTo("noodle");
    }

    @Test
    void menuEvidence_andMixedMenuFallsBackToCuisine() {
        // Nhà Hàng Ngon: no food in the name; menu names four catalog foods
        FoodClassification c = M.classify("Nhà Hàng Ngon", "Vietnamese",
            List.of("Bánh Xèo", "Bún Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt", "Phở Bò Tái"));
        assertThat(c.foodKeys()).containsExactly("bun", "pho", "banh-xeo", "nuong");   // catalog order
        assertThat(c.evidence().get("pho")).isEqualTo(FoodEvidence.menu("Phở Bò Tái"));
        assertThat(c.category()).isEqualTo("vietnamese");    // no 60 % menu majority → cuisineType
    }

    @Test
    void menuMajoritySetsTheCategory() {
        // text-level menu (no real menu-only restaurant has a majority today)
        FoodClassification c = M.classify("Quán Nhà", null, List.of("Phở Gà", "Bún Bò Huế", "Chả Giò"));
        assertThat(c.category()).isEqualTo("noodle");          // 2 of 3 dishes are noodle
        assertThat(M.classify("Quán Nhà", null, List.of("Phở Gà")).category()).isNull();   // one dish proves nothing
    }

    @Test
    void nameBeatsMenu_andMenuAddsOtherFoods() {
        FoodClassification c = M.classify("Bún Bò Huế Bà Hai", "Vietnamese",
            List.of("Bánh Canh Cua", "Bún Bò Huế", "Chả Giò"));
        assertThat(c.foodKeys()).containsExactly("bun-bo", "bun", "banh-canh", "cua-ghe");
        assertThat(c.evidence().get("bun-bo").source()).isEqualTo(FoodEvidence.Source.NAME);
        assertThat(c.evidence().get("banh-canh")).isEqualTo(FoodEvidence.menu("Bánh Canh Cua"));
        assertThat(c.category()).isEqualTo("noodle");
    }

    @Test
    void multipleFoodKeys_primaryIsEarliestInTheName() {
        assertThat(keys("Bún Thịt Nướng Cô Ba")).containsExactly("bun", "nuong");
        assertThat(keys("Nhà hàng chay Cát Vàng - Vegetarian Restaurant")).containsExactly("chay");
        assertThat(keys("IKI Sushi Sala-Premier Japanese Restaurant")).containsExactly("sushi", "mon-nhat");
    }

    @Test
    void vietnameseCuisineFallback() {
        FoodClassification c = M.classify("Quán Ông Năm", "Vietnamese", List.of());   // text-level name
        assertThat(c.foodKeys()).isEmpty();
        assertThat(c.category()).isEqualTo("vietnamese");
    }

    @Test
    void unknownRestaurant() {
        assertThat(M.classify("Restaurant 1 26", null, List.of())).isEqualTo(FoodClassification.NONE);
        assertThat(M.classify(null, null, null)).isEqualTo(FoodClassification.NONE);
    }

    // ── Query intent ─────────────────────────────────────────────────────────

    @Test
    void resolveQuery_keywordsLabelsAndAsciiForms() {
        assertThat(resolved("phở")).contains("pho");
        assertThat(resolved("PHỞ")).contains("pho");
        assertThat(resolved("pho")).contains("pho");
        assertThat(resolved("bún bò")).contains("bun-bo");
        assertThat(resolved("bun bo")).contains("bun-bo");
        assertThat(resolved("bun bò")).contains("bun-bo");          // mixed: each word by its own rule
        assertThat(resolved("cà phê")).contains("ca-phe");
        assertThat(resolved("cafe")).contains("ca-phe");
        assertThat(resolved("coffee")).contains("ca-phe");
        assertThat(resolved("trà sữa")).contains("tra-sua");
        assertThat(resolved("tra sua")).contains("tra-sua");
        assertThat(resolved("bánh mì")).contains("banh-mi");
        assertThat(resolved("banh mi")).contains("banh-mi");
        assertThat(resolved("lẩu")).contains("lau");
        assertThat(resolved("lau")).contains("lau");
        assertThat(resolved("món chay")).contains("chay");          // by label
    }

    @Test
    void resolveQuery_neverGuesses() {
        assertThat(resolved("phố")).isEmpty();
        assertThat(resolved("lầu")).isEmpty();
        assertThat(resolved("mi")).isEmpty();        // ASCII-unsafe: mì / mỹ …
        assertThat(resolved("chao")).isEmpty();      // ASCII-unsafe: cháo / chào
        assertThat(resolved("phở bò")).isEmpty();    // a longer query is not the food itself
        assertThat(resolved("ph")).isEmpty();
        assertThat(resolved("phong")).isEmpty();
        assertThat(resolved("  ")).isEmpty();
    }

    @Test
    void matchQuery_forTheSearchBox_discoverableOnly() {
        assertThat(M.matchQuery("phở", 8)).extracting(FoodEntry::key).containsExactly("pho");
        assertThat(M.matchQuery("phở bò tái", 8)).extracting(FoodEntry::key).containsExactly("pho");
        assertThat(M.matchQuery("bánh", 8)).extracting(FoodEntry::key).containsExactly("banh-mi", "banh-xeo");
        assertThat(M.matchQuery("trà s", 8)).extracting(FoodEntry::key).containsExactly("tra-sua");
        assertThat(M.matchQuery("bun", 8)).extracting(FoodEntry::key).startsWith("bun").contains("bun-bo");
        assertThat(M.matchQuery("trà", 8)).extracting(FoodEntry::key).containsExactly("tra-sua");   // "tra" itself is classification-only
        assertThat(M.matchQuery("phố", 8)).isEmpty();
        assertThat(M.matchQuery("xyz", 8)).isEmpty();
    }

    @Test
    void accentVariant_forAsciiQueries() {
        FoodEntry pho = M.vocabulary().get("pho").orElseThrow();
        assertThat(M.isAccentVariant(pho, VietnameseText.tokenize("Phố"))).isTrue();
        assertThat(M.isAccentVariant(pho, VietnameseText.tokenize("Phở"))).isFalse();
        assertThat(M.isAccentVariant(pho, VietnameseText.tokenize("Pho"))).isFalse();
        FoodEntry cafe = M.vocabulary().get("ca-phe").orElseThrow();
        assertThat(M.isAccentVariant(cafe, VietnameseText.tokenize("Café"))).isFalse();
    }
}
