package com.foodmap.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodCatalogLoaderTest {

    private static FoodVocabulary parse(String yaml) {
        return FoodCatalogLoader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /** A minimal valid two-entry catalog; tests break one rule at a time. */
    private static String catalog(String firstEntryOverrides) {
        return """
            foods:
              - key: pho
                label: Phở
                kind: MEAL
                parentCategory: noodle
                keywords: [phở]
                discoverable: true
                order: 10
            %s
            """.formatted(firstEntryOverrides);
    }

    private static String entry(String body) {
        return "  - " + body.strip().replace("\n", "\n    ");
    }

    private static final String BUN = """
        key: bun
        label: Bún
        kind: MEAL
        parentCategory: noodle
        keywords: [bún]
        discoverable: true
        order: 20""";

    // ── The real catalog ─────────────────────────────────────────────────────

    @Test
    void realCatalog_loadsWithTheSpecifiedDiscoverableFoodsInOrder() {
        FoodVocabulary v = FoodCatalogLoader.loadFromClasspath();
        assertThat(v.discoverable()).extracting(FoodEntry::key).containsExactly(
            "ca-phe", "tra-sua", "bun", "bun-bo", "pho", "hu-tieu", "mi", "com", "chao", "banh-mi",
            "banh-xeo", "lau", "nuong", "hai-san", "pizza", "sushi", "dimsum", "chay", "che", "kem");
        assertThat(v.entries()).hasSize(36);
        assertThat(v.entries().subList(20, 36)).allSatisfy(e -> assertThat(e.discoverable()).isFalse());
        assertThat(v.version()).hasSize(16);

        FoodEntry pho = v.get("pho").orElseThrow();
        assertThat(pho.label()).isEqualTo("Phở");
        assertThat(pho.kind()).isEqualTo(FoodKind.MEAL);
        assertThat(pho.parentCategory()).isEqualTo(FoodCategory.NOODLE);
        assertThat(pho.tone()).isEqualTo("chili");      // category default
        assertThat(pho.artKey()).isEqualTo("pho");      // defaults to key
        assertThat(v.get("ca-phe").orElseThrow().kind()).isEqualTo(FoodKind.DRINK);
        assertThat(v.get("kem").orElseThrow().kind()).isEqualTo(FoodKind.DESSERT);
    }

    @Test
    void validMinimalCatalog_loads() {
        FoodVocabulary v = parse(catalog(entry(BUN)));
        assertThat(v.discoverable()).extracting(FoodEntry::key).containsExactly("pho", "bun");
    }

    @Test
    void version_changesWhenTheFileChanges() {
        String a = parse(catalog(entry(BUN))).version();
        String b = parse(catalog(entry(BUN.replace("Bún", "Bún tươi")))).version();
        assertThat(a).isNotEqualTo(b);
        assertThat(parse(catalog(entry(BUN))).version()).isEqualTo(a);
    }

    // ── Rejections (each names the entry) ────────────────────────────────────

    private static void rejects(String yaml, String... messageParts) {
        var assertion = assertThatThrownBy(() -> parse(yaml)).isInstanceOf(InvalidFoodCatalogException.class);
        for (String part : messageParts) assertion.hasMessageContaining(part);
    }

    @Test
    void rejectsDuplicateKey() {
        rejects(catalog(entry(BUN.replace("key: bun", "key: pho").replace("[bún]", "[bún bò]").replace("Bún", "Bún bò"))),
            "'pho'", "duplicate key");
    }

    @Test
    void rejectsDuplicateDiscoverableOrder() {
        rejects(catalog(entry(BUN.replace("order: 20", "order: 10"))), "'bun'", "order 10");
    }

    @Test
    void rejectsMissingKeywords() {
        rejects(catalog(entry(BUN.replace("keywords: [bún]", "keywords: []"))), "'bun'", "at least one keyword");
        rejects(catalog(entry(BUN.replace("keywords: [bún]\n", ""))), "'bun'", "at least one keyword");
    }

    @Test
    void rejectsInvalidParentCategory() {
        rejects(catalog(entry(BUN.replace("parentCategory: noodle", "parentCategory: soup"))), "'bun'", "parentCategory 'soup'");
    }

    @Test
    void rejectsInvalidKind() {
        rejects(catalog(entry(BUN.replace("kind: MEAL", "kind: SNACK"))), "'bun'", "kind");
    }

    @Test
    void rejectsInvalidTone() {
        rejects(catalog(entry(BUN + "\ntone: neon")), "'bun'", "tone 'neon'");
    }

    @Test
    void rejectsInvalidSlugsAndReservedKey() {
        rejects(catalog(entry(BUN.replace("key: bun", "key: Bún"))), "slug");
        rejects(catalog(entry(BUN.replace("key: bun", "key: available"))), "reserved");
        rejects(catalog(entry(BUN + "\nartKey: Bun Art")), "'bun'", "artKey");
    }

    @Test
    void rejectsMissingLabel_unknownField_andDiscoverableWithoutOrder() {
        rejects(catalog(entry(BUN.replace("label: Bún\n", ""))), "'bun'", "'label' is required");
        rejects(catalog(entry(BUN + "\npopularity: 9")), "'bun'", "unknown field 'popularity'");
        rejects(catalog(entry(BUN.replace("\norder: 20", ""))), "'bun'", "needs an order");
        rejects(catalog(entry(BUN.replace("discoverable: true", "discoverable: yes please"))), "'bun'", "discoverable");
    }

    @Test
    void rejectsAPhraseClaimedByTwoFoods() {
        rejects(catalog(entry(BUN.replace("[bún]", "[bún, phở]"))), "'bun'", "'phở'", "'pho'");
    }

    @Test
    void rejectsAsciiSafeFoldedCollisionsAcrossFoods() {
        // "phố" is a different word, but both fold to "pho" — an ASCII query would be ambiguous
        rejects(catalog(entry(BUN.replace("[bún]", "[bún, phố]"))), "'bun'", "folds");
        // marking it asciiSafe: false removes the ambiguity
        assertThat(parse(catalog(entry(BUN.replace("[bún]", "[bún, {text: phố, asciiSafe: false}]")))).entries())
            .hasSize(2);
    }

    @Test
    void rejectsMalformedRoot() {
        rejects("nothing: here", "'foods'");
        rejects("foods: [", "not valid YAML");
    }

    @Test
    void classificationOnlyEntries_needNoOrder() {
        FoodVocabulary v = parse(catalog(entry(BUN.replace("discoverable: true", "discoverable: false").replace("\norder: 20", ""))));
        assertThat(v.discoverable()).extracting(FoodEntry::key).containsExactly("pho");
        assertThat(v.entries()).extracting(FoodEntry::key).isEqualTo(List.of("pho", "bun"));
    }
}
