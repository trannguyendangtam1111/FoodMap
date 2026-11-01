package com.foodmap.restaurant.search;

import com.foodmap.common.text.VietnameseText.Quality;
import com.foodmap.restaurant.search.SearchRanker.Candidate;
import com.foodmap.restaurant.search.SearchRanker.Origin;
import com.foodmap.restaurant.search.SearchRanker.Ranked;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchRanker against real rows of the FoodMap dev database (snapshot
 * 2026-10-03: ids, names, addresses, ratings, coordinates and menus copied
 * verbatim). nameSimilarity values are the pg_trgm similarities PostgreSQL
 * returns for the folded name/query pair, except where a test sets one to
 * exercise a single rule. No database is needed.
 */
class SearchRankerTest {

    // ── Real rows ────────────────────────────────────────────────────────────

    private static Candidate row(long id, String name, String address, String rating, int reviews,
                                 double lat, double lng, String... dishes) {
        return new Candidate(id, name, address, new BigDecimal(rating), reviews, lat, lng, 0.0, List.of(dishes));
    }

    private static Candidate withSimilarity(Candidate c, double similarity) {
        return new Candidate(c.id(), c.name(), c.address(), c.avgRating(), c.reviewCount(),
            c.latitude(), c.longitude(), similarity, c.dishNames());
    }

    static final Candidate PHO_HOA_PASTEUR = row(1, "Phở Hòa Pasteur", "260C Pasteur, Phường 8, Quận 3, TP.HCM",
        "4.50", 4, 10.77620, 106.68920, "Gỏi Cuốn Tôm Thịt", "Phở Bò Tái", "Phở Gà");
    static final Candidate LAU_MAM = row(5, "Lẩu Mắm Ngọc Giàu", "123 Đinh Tiên Hoàng, Phường 3, Quận Bình Thạnh, TP.HCM",
        "4.67", 3, 10.81020, 106.70830, "Bánh Xèo", "Bún Mắm", "Gỏi Cuốn Tôm Thịt", "Lẩu Mắm");
    static final Candidate NHA_HANG_NGON = row(9, "Nhà Hàng Ngon", "160 Pasteur, Phường Võ Thị Sáu, Quận 3, TP.HCM",
        "4.33", 3, 10.77790, 106.69520, "Bánh Xèo", "Bún Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt", "Phở Bò Tái");
    static final Candidate PHO_26 = row(26, "Phở", "Phở, 161D/36, Phường 3, Quận 11, Thành Phố Hồ Chí Minh", "0.00", 0, 10.76265, 106.64040);
    static final Candidate PHO_27 = row(27, "Phở", "Phở, 187B Ngõ Hoàng Sa, Phường Tân Định, Quận 1, Thành Phố Hồ Chí Minh", "0.00", 0, 10.79526, 106.69105);
    static final Candidate PHO_28 = row(28, "Phở", "Phở, Đường Số 2, phường 16, quận Gò Vấp, Hồ Chí Minh", "0.00", 0, 10.84979, 106.66196);
    static final Candidate PHO_29 = row(29, "Phở", "Phở, Nguyễn Trường Tộ, Phường Tân Thành, Tân Phú, Hồ Chí Minh", "0.00", 0, 10.79262, 106.63604);
    static final Candidate PHO_52 = row(52, "Phở", "Phở, 4 An Phú - An Thạnh, Phường An Phú, Thành Phố Thuận An, Tỉnh Bình Dương", "0.00", 0, 10.94835, 106.73474);
    static final Candidate BUN_BO_34 = row(34, "Bún Bò", "Bún Bò, 246 Phạm Hữu Lầu, Phú Mỹ, Quận 7, Thành phố Hồ Chí Minh", "0.00", 0, 10.70422, 106.72836);
    static final Candidate TRA_SUA_HASH_36 = row(36, "Trà Sữa #", "Trà Sữa #, 37 Sơn Kỳ, Sơn Kỳ, Tân Phú, Hồ Chí Minh", "0.00", 0, 10.80506, 106.62765);
    static final Candidate TRA_SUA_53 = row(53, "Trà sữa", "Trà sữa, 242 Bà Hom, Phường 14, Quận 6, Hồ Chí Minh", "0.00", 0, 10.75779, 106.62679);
    static final Candidate TRA_SUA_55 = row(55, "Trà sữa", "Trà sữa, 433 Đường Lê Văn Quới, Bình Trị Đông A, Bình Tân, Thành phố Hồ Chí Minh", "0.00", 0, 10.77616, 106.60505);
    static final Candidate TRA_SUA_KOI = row(75, "Trà sữa Koi", "Trà sữa Koi, 569 Lê Văn Lương, Tân Phong, Quận 7, Hồ Chí Minh", "0.00", 0, 10.73362, 106.70185);
    static final Candidate BUN_DAU_82 = row(82, "Bún Đậu Ngõ Nhỏ Phố Nhỏ", "Bún Đậu Ngõ Nhỏ Phố Nhỏ, 156C Pasteur, Bến Nghé, Quận 1, Hồ Chí Minh", "0.00", 0, 10.77703, 106.69978);
    static final Candidate CAFE_PHO_103 = row(103, "Cafe Phố", "Cafe Phố, 213F Nguyễn Văn Cừ, Phường 3, Quận 5, Hồ Chí Minh", "0.00", 0, 10.76074, 106.68331);
    static final Candidate CAFE_THANH_PHO_MOI = row(144, "Cafe Thành Phố Mới", "Cafe Thành Phố Mới, 42 Vũ Tông Phan, An Phú, Thủ Đức, Hồ Chí Minh", "0.00", 0, 10.79728, 106.74215);
    static final Candidate CAFE_SONG_TRA = row(159, "Cafe Song Trà", "Cafe Song Trà, 256 Huỳnh Tấn Phát, Tân Thuận Tây, Quận 7, Hồ Chí Minh", "0.00", 0, 10.75384, 106.72823);
    static final Candidate CAFE_DA_LAT_PHO = row(232, "Cafe Đà Lạt Phố", "Cafe Đà Lạt Phố, 153 Huỳnh Mẫn Đạt, Phường 8, Quận 5, Hồ Chí Minh", "0.00", 0, 10.75682, 106.67604);
    static final Candidate GOC_PHO_RESTAURANT = row(239, "Góc Phố Restaurant", "Góc Phố Restaurant, Lô C15B - Khu căn hộ Star Hill, 24 Raymondienne, Tân Phú, Quận 7, Hồ Chí Minh", "0.00", 0, 10.72876, 106.72628);
    static final Candidate CAFE_GOC_PHO = row(252, "Cafe Góc Phố", "Cafe Góc Phố, 82 Đường Số 7, An Phú, Thủ Đức, Hồ Chí Minh", "0.00", 0, 10.79938, 106.73779);
    static final Candidate CAFE_TOWN_ERA = row(285, "Cafe The Town Era", "Cafe The Town Era, Block A - Era Town Đức Khải, 79/4 Phạm Hữu Lầu, Phú Mỹ, Quận 7, Hồ Chí Minh", "0.00", 0, 10.70139, 106.73333);
    static final Candidate SALAD_RESTAURANT = row(302, "Salad Restaurant", "Salad Restaurant, Lầu 4, 119-119C Hòa Hưng, Phường 12, Quận 10, Hồ Chí Minh", "0.00", 0, 10.77858, 106.67286);
    static final Candidate CAFE_PHO_314 = row(314, "Cafe Phố", "Cafe Phố, 513 Hưng Phú, Phường 9, Quận 8, Hồ Chí Minh", "0.00", 0, 10.74691, 106.67254);
    static final Candidate P2T_RESTAURANT = row(336, "P2T Restaurant", "P2T Restaurant, Lầu 3, Flemington Tower, 182 Lê Đại Hành, Phường 15, Quận 11, Hồ Chí Minh", "0.00", 0, 10.76398, 106.65613);
    static final Candidate VIET_PHO = row(409, "Viet Pho Restaurant", "Viet Pho Restaurant, 2 Đường số 24, Phường 11, Quận 6, Hồ Chí Minh", "0.00", 0, 10.74248, 106.62972);
    static final Candidate CAFE_GOC_PHO_432 = row(432, "Cafe Gốc Phố", "Cafe Gốc Phố, Phú Thuận, Quận 7, Hồ Chí Minh", "0.00", 0, 10.72830, 106.73299);
    static final Candidate CAFE_PHO_473 = row(473, "Cafe Phố", "Cafe Phố, 9 Hoa Cúc, Phường 7, Phú Nhuận, Hồ Chí Minh", "0.00", 0, 10.79899, 106.68808);
    static final Candidate CAFE_TH = row(486, "Cafe T&H", "Cafe T&H, 104 Phổ Quang, Phường 2, Tân Bình, Hồ Chí Minh", "0.00", 0, 10.80867, 106.66953);
    static final Candidate CAFE_TRA = row(534, "Cafe Trà", "Cafe Trà, 781 Đoàn Văn Bơ, Phường 18, Quận 4, Hồ Chí Minh", "0.00", 0, 10.75670, 106.71663);
    static final Candidate CAFE_16 = row(16, "cafe", "cafe, 142, 7 Cô Giang, Phường Cô Giang, Quận 1, Hồ Chí Minh", "0.00", 0, 10.76276, 106.69391);
    static final Candidate CAFE_18 = row(18, "Cafe", "Cafe, 83/531 Đ. Số 7, Phường 15, Tân Bình, Hồ Chí Minh", "0.00", 0, 10.82377, 106.67605);
    static final Candidate RESTAURANT_CAFE_122 = row(122, "Restaurant & cafe Bon Appetit", "Restaurant & cafe Bon Appetit, 30 Đinh Công Tráng, Tân Định, Quận 1, Hồ Chí Minh", "0.00", 0, 10.78991, 106.69167);
    static final Candidate LA_GARDEN_141 = row(141, "LA garden Restaurant & Cafe", "LA garden Restaurant & Cafe, A13/1BIS Lương Định Của, Thủ Thiêm, Thủ Đức, Hồ Chí Minh", "0.00", 0, 10.78414, 106.72321);
    static final Candidate PIZZA_HUT_21 = row(21, "Pizza Hut", "Pizza Hut, 941 Lũy Bán Bích, Tân Thành, Tân Phú, Hồ Chí Minh", "0.00", 0, 10.79167, 106.63750);

    /** Every row stage 1 returned for "phở"/"pho" that this test file has (folded matches over-retrieve). */
    static final List<Candidate> PHO_POOL = List.of(
        PHO_HOA_PASTEUR, NHA_HANG_NGON, PHO_26, PHO_27, PHO_28, PHO_29, PHO_52, TRA_SUA_55, BUN_DAU_82,
        CAFE_PHO_103, CAFE_THANH_PHO_MOI, CAFE_DA_LAT_PHO, GOC_PHO_RESTAURANT, CAFE_GOC_PHO, CAFE_PHO_314,
        VIET_PHO, CAFE_GOC_PHO_432, CAFE_PHO_473, CAFE_TH);

    private static List<Long> ids(List<Ranked> ranked) {
        return ranked.stream().map(r -> r.candidate().id()).toList();
    }

    private static List<Ranked> rank(String query, List<Candidate> pool) {
        return SearchRanker.rank(query, pool, null);
    }

    private static Ranked only(String query, Candidate c) {
        List<Ranked> r = rank(query, List.of(c));
        assertThat(r).hasSize(1);
        return r.get(0);
    }

    // ── Tiers ────────────────────────────────────────────────────────────────

    @Test
    void t1_exactName() {
        assertThat(only("Trà sữa", TRA_SUA_53).tier()).isEqualTo(SearchTier.EXACT_NAME);
        assertThat(only("trà sữa", TRA_SUA_HASH_36).tier()).isEqualTo(SearchTier.EXACT_NAME); // "#" is not a word
    }

    @Test
    void t2_wholeWordName() {
        assertThat(only("phở", PHO_HOA_PASTEUR).tier()).isEqualTo(SearchTier.WHOLE_WORD_NAME);
        assertThat(only("trà sữa", TRA_SUA_KOI).tier()).isEqualTo(SearchTier.WHOLE_WORD_NAME);
    }

    @Test
    void t3_foodMenu_onlyFromRealMenuEvidence() {
        Ranked r = only("phở bò tái", NHA_HANG_NGON);
        assertThat(r.tier()).isEqualTo(SearchTier.FOOD_MENU);
        // A Goong-only row has no menu, so a dish query cannot reach T3 for it
        assertThat(rank("phở bò tái", List.of(PHO_26))).isEmpty();
    }

    @Test
    void t4_partialFinalToken() {
        List<Ranked> r = rank("trà s", List.of(TRA_SUA_HASH_36, TRA_SUA_53, TRA_SUA_KOI, CAFE_SONG_TRA, CAFE_TRA));
        assertThat(ids(r)).containsExactly(36L, 53L, 75L);
        assertThat(r).allSatisfy(x -> assertThat(x.tier()).isEqualTo(SearchTier.FUZZY_NAME));
    }

    @Test
    void t4_trigramFuzzy() {
        // "piza hut" ↔ "Pizza Hut": pg_trgm similarity 0.7273
        Ranked r = only("piza hut", withSimilarity(PIZZA_HUT_21, 0.7273));
        assertThat(r.tier()).isEqualTo(SearchTier.FUZZY_NAME);
        assertThat(rank("piza hut", List.of(withSimilarity(PIZZA_HUT_21, 0.29)))).isEmpty();
    }

    @Test
    void t4_fuzzyNeedsAtLeastFourFoldedLetters() {
        Candidate pizza = withSimilarity(PIZZA_HUT_21, 0.5);
        assertThat(rank("zza", List.of(pizza))).isEmpty();
        assertThat(rank("izza", List.of(pizza))).extracting(Ranked::tier).containsExactly(SearchTier.FUZZY_NAME);
    }

    @Test
    void t4_fuzzyNeverCrossesAnAccentConflict() {
        // "phở bò" ↔ "Cafe Phố": pg_trgm similarity 0.3333 on folded text, but phở ≠ phố
        assertThat(rank("phở bò", List.of(withSimilarity(CAFE_PHO_103, 0.3333)))).isEmpty();
        // "phở cafe" ↔ "Cafe Phố": similarity 1.0 (same words, other order), rejected only by the conflict
        assertThat(rank("phở cafe", List.of(withSimilarity(CAFE_PHO_103, 1.0)))).isEmpty();
        // the same pair without the conflict is accepted
        assertThat(rank("pho cafe", List.of(withSimilarity(CAFE_PHO_103, 1.0))))
            .extracting(Ranked::tier).containsExactly(SearchTier.FUZZY_NAME);
    }

    @Test
    void t4_fuzzyNeverMatchesOnOneSharedWordAlone() {
        // "bún chả" ↔ "Bún Bò": pg_trgm similarity 0.40, all of it from "bún"; "chả" has no counterpart
        assertThat(rank("bún chả", List.of(withSimilarity(BUN_BO_34, 0.40)))).isEmpty();
        assertThat(rank("bun cha", List.of(withSimilarity(BUN_BO_34, 0.40)))).isEmpty();
        // "pho bo" ↔ "Cafe Phố": 0.3333, all of it from "pho"
        assertThat(rank("pho bo", List.of(withSimilarity(CAFE_PHO_103, 0.3333)))).isEmpty();
    }

    @Test
    void t4_fuzzyStillAcceptsATypoInEveryWord() {
        // "pho hoa pastuer" ↔ "Phở Hòa Pasteur": pg_trgm similarity 0.5789; pastuer ~ pasteur
        assertThat(only("pho hoa pastuer", withSimilarity(PHO_HOA_PASTEUR, 0.5789)).tier())
            .isEqualTo(SearchTier.FUZZY_NAME);
        // "phở hòa" itself is a whole-word name match, untouched by the fuzzy rule
        assertThat(only("phở hòa", PHO_HOA_PASTEUR).tier()).isEqualTo(SearchTier.WHOLE_WORD_NAME);
    }

    @Test
    void t5_addressOnly_neverAboveNameOrMenu() {
        List<Ranked> r = rank("pasteur", List.of(BUN_DAU_82, NHA_HANG_NGON, PHO_HOA_PASTEUR));
        assertThat(ids(r)).containsExactly(1L, 9L, 82L);
        assertThat(r).extracting(Ranked::tier)
            .containsExactly(SearchTier.WHOLE_WORD_NAME, SearchTier.ADDRESS_ONLY, SearchTier.ADDRESS_ONLY);
    }

    @Test
    void t5_addressPrefixStripping() {
        assertThat(SearchRanker.addressWithoutName(PHO_27.address(), PHO_27.name()))
            .isEqualTo("187B Ngõ Hoàng Sa, Phường Tân Định, Quận 1, Thành Phố Hồ Chí Minh");
        assertThat(SearchRanker.addressWithoutName(CAFE_TH.address(), CAFE_TH.name()))
            .isEqualTo("104 Phổ Quang, Phường 2, Tân Bình, Hồ Chí Minh");
        // an address that does not start with the name is kept whole
        assertThat(SearchRanker.addressWithoutName(PHO_HOA_PASTEUR.address(), PHO_HOA_PASTEUR.name()))
            .isEqualTo(PHO_HOA_PASTEUR.address());
    }

    // ── Single-word band ─────────────────────────────────────────────────────

    @Test
    void singleWordBand_exactGenericNameDoesNotOutrankALongerRelevantName() {
        List<Ranked> r = rank("phở", List.of(PHO_26, PHO_HOA_PASTEUR));
        assertThat(r.get(0).tier()).isEqualTo(SearchTier.WHOLE_WORD_NAME);   // Phở Hòa Pasteur
        assertThat(ids(r)).containsExactly(1L, 26L);
    }

    @Test
    void multiWordQuery_keepsExactAboveWholeWord() {
        // a rated T2 row would win inside a band; with two words T1 stays its own tier
        Candidate ratedKoi = row(75, "Trà sữa Koi", TRA_SUA_KOI.address(), "4.90", 9, 10.73362, 106.70185);
        assertThat(ids(rank("trà sữa", List.of(ratedKoi, TRA_SUA_53)))).containsExactly(53L, 75L);
    }

    // ── The contract examples ────────────────────────────────────────────────

    @Test
    void query_pho_accented() {
        List<Ranked> r = rank("phở", PHO_POOL);
        assertThat(ids(r)).containsExactly(1L, 26L, 27L, 28L, 29L, 52L, 409L, 9L);
        assertThat(r.get(6).quality()).isEqualTo(Quality.ASCII_FALLBACK);    // Viet Pho, below the accent-exact band
        assertThat(r.get(7).tier()).isEqualTo(SearchTier.FOOD_MENU);         // Nhà Hàng Ngon
        // nothing that matches only through phố / phổ / an address
        assertThat(ids(r)).doesNotContain(55L, 82L, 103L, 144L, 232L, 239L, 252L, 314L, 432L, 473L, 486L);
    }

    @Test
    void query_pho_ascii_foldsButPutsPhoNamesFirst() {
        List<Ranked> r = rank("pho", PHO_POOL);
        assertThat(ids(r).subList(0, 6)).containsExactly(1L, 26L, 27L, 28L, 29L, 52L);   // names starting "Phở"
        assertThat(ids(r).subList(6, 16)).containsExactly(82L, 103L, 144L, 232L, 239L, 252L, 314L, 409L, 432L, 473L);
        assertThat(ids(r).get(16)).isEqualTo(9L);                                         // menu
        assertThat(ids(r).subList(17, 19)).containsExactly(55L, 486L);                    // address-only, last
        assertThat(r.get(17).tier()).isEqualTo(SearchTier.ADDRESS_ONLY);
    }

    @Test
    void query_lau_neverMatchesLau_floor() {
        List<Candidate> pool = List.of(LAU_MAM, BUN_BO_34, CAFE_TOWN_ERA, SALAD_RESTAURANT, P2T_RESTAURANT);
        assertThat(ids(rank("lẩu", pool))).containsExactly(5L);
        // the unaccented query may reach the "Lầu" addresses, but only as address-only, after the name match
        List<Ranked> ascii = rank("lau", pool);
        assertThat(ids(ascii)).containsExactly(5L, 34L, 285L, 302L, 336L);
        assertThat(ascii.subList(1, 5)).allSatisfy(x -> assertThat(x.tier()).isEqualTo(SearchTier.ADDRESS_ONLY));
    }

    @Test
    void query_cafe_namesStartingWithCafeFirst() {
        List<Ranked> r = rank("cafe", List.of(LA_GARDEN_141, RESTAURANT_CAFE_122, CAFE_PHO_103, CAFE_18, CAFE_16));
        assertThat(ids(r)).containsExactly(16L, 18L, 103L, 122L, 141L);
    }

    @Test
    void query_traSua_neverMatchesOnlyTraOrOnlySua() {
        List<Ranked> r = rank("trà sữa", List.of(CAFE_SONG_TRA, CAFE_TRA, TRA_SUA_KOI, TRA_SUA_53, TRA_SUA_HASH_36));
        assertThat(ids(r)).containsExactly(36L, 53L, 75L);
    }

    @Test
    void accentedQuery_neverPrefixMatchesAnUnrelatedAsciiWord() {
        Candidate phongLan = row(395, "Phong Lan Restaurant (Sasco)",
            "Phong Lan Restaurant (Sasco), Ga Quốc Tế - Sân bay Quốc tế Tân Sơn Nhất, Phường 2, Tân Bình, Hồ Chí Minh",
            "0.00", 0, 10.81595, 106.66428);
        assertThat(rank("phở", List.of(phongLan))).isEmpty();
        // typing the unaccented "pho" may still be the start of "phong" → partial tier, after everything else
        assertThat(rank("pho", List.of(phongLan))).extracting(Ranked::tier).containsExactly(SearchTier.FUZZY_NAME);
    }

    @Test
    void addressCarryingTheVietnameseName_isAnAddressOnlyMatch() {
        // Goong's formatted address for this row starts with its Vietnamese name "Lẩu nấm chay An Nhiên 1"
        Candidate anNhien = row(518, "An Nhien 1 Vegetarian Restaurant",
            "Lẩu nấm chay An Nhiên 1, 8A-10 Phạm Ngũ Lão, Phường 3, Gò Vấp, Hồ Chí Minh", "0.00", 0, 10.82536, 106.68061);
        List<Ranked> r = rank("lẩu", List.of(anNhien, LAU_MAM));
        assertThat(ids(r)).containsExactly(5L, 518L);
        assertThat(r.get(1).tier()).isEqualTo(SearchTier.ADDRESS_ONLY);
    }

    @Test
    void oldAndNewTonePlacementMatch() {
        assertThat(only("phở hoà", PHO_HOA_PASTEUR).tier()).isEqualTo(SearchTier.WHOLE_WORD_NAME);
    }

    // ── Tie-breaks ───────────────────────────────────────────────────────────

    @Test
    void ratingBreaksTiesWithinATier_rated_thenHigherRating() {
        // all three have "Gỏi Cuốn Tôm Thịt" on the menu: 4.67 (3), 4.50 (4), 4.33 (3)
        assertThat(ids(rank("gỏi cuốn", List.of(NHA_HANG_NGON, PHO_HOA_PASTEUR, LAU_MAM)))).containsExactly(5L, 1L, 9L);
    }

    @Test
    void ratingNeverLiftsAResultAcrossTiers() {
        // Nhà Hàng Ngon is rated 4.33 but only has phở on its menu (T3) → after unrated name matches
        List<Long> r = ids(rank("phở", List.of(NHA_HANG_NGON, PHO_26)));
        assertThat(r).containsExactly(26L, 9L);
    }

    @Test
    void userDistanceBreaksRemainingTies_only() {
        Origin nearBinhDuong = new Origin(10.94835, 106.73474);   // at row 52
        List<Ranked> r = SearchRanker.rank("phở", PHO_POOL, nearBinhDuong);
        // rated row 1 still first; unrated "Phở" rows now nearest-first; tiers unchanged
        assertThat(ids(r)).containsExactly(1L, 52L, 28L, 27L, 29L, 26L, 409L, 9L);
        assertThat(r.get(1).distanceMeters()).isLessThan(1.0);
        assertThat(SearchRanker.rank("phở", PHO_POOL, null)).allSatisfy(x -> assertThat(x.distanceMeters()).isNull());
    }

    @Test
    void ordering_isStableRegardlessOfInputOrder() {
        List<Long> expected = ids(rank("pho", PHO_POOL));
        for (int seed = 0; seed < 5; seed++) {
            List<Candidate> shuffled = new ArrayList<>(PHO_POOL);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(ids(rank("pho", shuffled))).isEqualTo(expected);
        }
    }

    @Test
    void emptyOrPunctuationQuery_returnsNothing() {
        assertThat(rank("  #! ", PHO_POOL)).isEmpty();
    }
}
