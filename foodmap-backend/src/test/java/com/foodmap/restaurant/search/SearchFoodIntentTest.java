package com.foodmap.restaurant.search;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.search.SearchRanker.Candidate;
import com.foodmap.restaurant.search.SearchRanker.Ranked;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.foodmap.restaurant.search.SearchRankerTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part 2 food intent on real dev-DB rows (fixtures shared with
 * SearchRankerTest, plus a few more copied verbatim). The intent is built
 * exactly as RestaurantSearchService builds it, from the real catalog.
 */
class SearchFoodIntentTest {

    private static final FoodMatcher MATCHER = new FoodMatcher(FoodCatalogLoader.loadFromClasspath());

    private static Candidate row(long id, String name, String address, String rating, int reviews,
                                 double lat, double lng, String... dishes) {
        return new Candidate(id, name, address, new BigDecimal(rating), reviews, lat, lng, 0.0, List.of(dishes));
    }

    static final Candidate PHONG_LAN = row(395, "Phong Lan Restaurant (Sasco)",
        "Phong Lan Restaurant (Sasco), Ga Quốc Tế - Sân bay Quốc tế Tân Sơn Nhất, Phường 2, Tân Bình, Hồ Chí Minh",
        "0.00", 0, 10.81595, 106.66428);
    static final Candidate BUN_BO_HUE = row(2, "Bún Bò Huế Bà Hai", "84 Lê Văn Sỹ, Phường 13, Quận 3, TP.HCM",
        "4.33", 3, 10.78470, 106.68240, "Bánh Canh Cua", "Bún Bò Huế", "Chả Giò");
    static final Candidate BANH_MI = row(4, "Bánh Mì Huỳnh Hoa", "26 Lê Thị Riêng, Phường Bến Thành, Quận 1, TP.HCM",
        "4.67", 3, 10.77640, 106.70080, "Bánh Mì Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt");
    static final Candidate BUN_BO_66 = row(66, "Bún Bò", "Bún Bò, 280 Phạm Văn Đồng, Phường 1, Gò Vấp, Thành phố Hồ Chí Minh",
        "0.00", 0, 10.81868, 106.68863);
    static final Candidate BUN_BO_73 = row(73, "Bún bò", "Bún bò, Phạm Văn Hai, Phường 1, Tân Bình, Hồ Chí Minh",
        "0.00", 0, 10.79567, 106.66568);
    static final Candidate ANNA_VILLA = row(169, "Anna Villa Coffee & Restaurant",
        "Anna Villa Coffee & Restaurant, 62 Nam Hòa, Phước Long A, Thủ Đức, Hồ Chí Minh", "0.00", 0, 10.82379, 106.76116);
    static final Candidate SWEET_COFFEE = row(483, "The Sweet Coffee",
        "The Sweet restaurant & cafe, 46 Trương Quốc Dụng, Phường 10, Phú Nhuận, Hồ Chí Minh", "0.00", 0, 10.79689, 106.67257);
    static final Candidate AN_NHIEN = row(518, "An Nhien 1 Vegetarian Restaurant",
        "Lẩu nấm chay An Nhiên 1, 8A-10 Phạm Ngũ Lão, Phường 3, Gò Vấp, Hồ Chí Minh", "0.00", 0, 10.82536, 106.68061);

    private static List<Long> ids(List<Ranked> r) {
        return r.stream().map(x -> x.candidate().id()).toList();
    }

    /** Ranks like the service: intent resolved from the catalog, or none. */
    private static List<Ranked> search(String query, List<Candidate> pool) {
        FoodIntent intent = MATCHER.resolveQuery(query)
            .map(e -> RestaurantSearchService.intentFor(e, MATCHER)).orElse(null);
        return SearchRanker.rank(query, pool, null, intent);
    }

    private static List<Candidate> with(List<Candidate> base, Candidate... more) {
        List<Candidate> all = new ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }

    @Test
    void pho_ascii_foodIntentOrdersPhoBeforePhoVariants_andDropsAddresses() {
        List<Ranked> r = search("pho", with(PHO_POOL, PHONG_LAN));
        assertThat(ids(r)).containsExactly(
            1L, 26L, 27L, 28L, 29L, 52L,                 // names starting with Phở (rated first)
            409L,                                        // Viet Pho Restaurant (ASCII name, later in the name)
            9L,                                          // Nhà Hàng Ngon: Phở Bò Tái on the menu (T3)
            395L,                                        // Phong…: partial word (T4)
            82L, 103L, 144L, 232L, 239L, 252L, 314L, 432L, 473L);   // "Phố" names: accent variants, bottom of T4
        assertThat(r.subList(9, 18)).allSatisfy(x -> {
            assertThat(x.tier()).isEqualTo(SearchTier.FUZZY_NAME);
            assertThat(x.accentVariant()).isTrue();
        });
        assertThat(ids(r)).doesNotContain(55L, 486L);    // address-only ("Thành phố", "Phổ Quang")
    }

    @Test
    void pho_accented_keepsPart1Result() {
        assertThat(ids(search("phở", with(PHO_POOL, PHONG_LAN)))).containsExactly(1L, 26L, 27L, 28L, 29L, 52L, 409L, 9L);
    }

    @Test
    void cafe_synonymsReachT3_suppressedNamesDoNot() {
        List<Ranked> r = search("cafe", List.of(LA_GARDEN_141, RESTAURANT_CAFE_122, CAFE_PHO_103, CAFE_18, CAFE_16,
            CAFE_SONG_TRA, SWEET_COFFEE, ANNA_VILLA));
        assertThat(ids(r)).containsExactly(16L, 18L, 103L, 159L, 122L, 141L, 483L);
        assertThat(r.get(6).tier()).isEqualTo(SearchTier.FOOD_MENU);      // The Sweet Coffee: "coffee" = cà phê
        assertThat(ids(r)).doesNotContain(169L);                          // "Coffee & Restaurant": suppressed
    }

    @Test
    void caPhe_accented_findsCafesThroughSynonyms() {
        List<Ranked> r = search("cà phê", List.of(CAFE_16, SWEET_COFFEE, RESTAURANT_CAFE_122, CAFE_PHO_103));
        assertThat(ids(r)).containsExactly(16L, 103L, 483L);
        assertThat(r).allSatisfy(x -> assertThat(x.tier()).isEqualTo(SearchTier.FOOD_MENU));
    }

    @Test
    void lau_and_lauAccented_neverMatchFloorsOrAddresses() {
        List<Candidate> pool = List.of(LAU_MAM, BUN_BO_34, CAFE_TOWN_ERA, SALAD_RESTAURANT, P2T_RESTAURANT, AN_NHIEN);
        assertThat(ids(search("lẩu", pool))).containsExactly(5L);
        assertThat(ids(search("lau", pool))).containsExactly(5L);       // Part 1 had the "Lầu" addresses here
    }

    @Test
    void bunBo_bothForms() {
        List<Candidate> pool = List.of(BUN_BO_HUE, BUN_BO_66, BUN_BO_73, BUN_BO_34, BUN_DAU_82, LAU_MAM);
        // two words: exact names (T1) before "Bún Bò Huế …" (T2)
        assertThat(ids(search("bún bò", pool))).containsExactly(34L, 66L, 73L, 2L);
        assertThat(ids(search("bun bo", pool))).containsExactly(34L, 66L, 73L, 2L);
    }

    @Test
    void traSua_bothForms_neverOnlyTra() {
        List<Candidate> pool = List.of(TRA_SUA_HASH_36, TRA_SUA_53, TRA_SUA_KOI, CAFE_SONG_TRA, CAFE_TRA);
        assertThat(ids(search("trà sữa", pool))).containsExactly(36L, 53L, 75L);
        assertThat(ids(search("tra sua", pool))).containsExactly(36L, 53L, 75L);
    }

    @Test
    void banhMi_bothForms() {
        List<Candidate> pool = List.of(BANH_MI, PHO_HOA_PASTEUR, LAU_MAM);
        assertThat(ids(search("bánh mì", pool))).containsExactly(4L);
        assertThat(ids(search("banh mi", pool))).containsExactly(4L);
    }

    @Test
    void menuEvidence_forTheResolvedFood_isT3() {
        // "bánh canh" is only on Bún Bò Huế Bà Hai's menu → T3 for a bánh canh search
        assertThat(search("bánh canh", List.of(BUN_BO_HUE))).extracting(Ranked::tier)
            .containsExactly(SearchTier.FOOD_MENU);
    }

    @Test
    void nonFoodQueries_keepAddressMatches() {
        // "pasteur" names no food → Part 1 behaviour, address-only tier still applies
        List<Ranked> r = search("pasteur", List.of(BUN_DAU_82, NHA_HANG_NGON, PHO_HOA_PASTEUR));
        assertThat(ids(r)).containsExactly(1L, 9L, 82L);
        assertThat(r.get(2).tier()).isEqualTo(SearchTier.ADDRESS_ONLY);
    }

    @Test
    void likePatterns_coverEverySynonym_folded() {
        assertThat(RestaurantSearchService.likePatterns(MATCHER.vocabulary().get("ca-phe").orElseThrow()))
            .isEqualTo("%ca phe%|%cafe%|%coffee%");
        assertThat(RestaurantSearchService.likePatterns(MATCHER.vocabulary().get("pho").orElseThrow()))
            .isEqualTo("%pho%");
    }
}
