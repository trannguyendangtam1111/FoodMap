package com.foodmap.config;

import com.foodmap.food.domain.Food;
import com.foodmap.food.domain.FoodTag;
import com.foodmap.food.repository.FoodRepository;
import com.foodmap.food.repository.FoodTagRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.domain.RestaurantFood;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.review.domain.Review;
import com.foodmap.review.repository.ReviewRepository;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import com.foodmap.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final RestaurantRepository restaurantRepository;
    private final FoodRepository       foodRepository;
    private final FoodTagRepository    foodTagRepository;
    private final ReviewRepository     reviewRepository;
    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;

    /**
     * Shared password for the seeded demo accounts (dev profile only), bound from
     * SEED_DEMO_PASSWORD via app.seed.demo-password in application-dev.yml.
     * There is deliberately no built-in value: credentials never live in source.
     * Only needed when seeding an empty database.
     */
    @Value("${app.seed.demo-password:}")
    private String demoPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (restaurantRepository.count() > 0) {
            log.info("[DataSeeder] Seed data already present — skipping.");
            return;
        }
        requireDemoPassword(); // fail before inserting anything

        log.info("[DataSeeder] Seeding FoodMap development data for Ho Chi Minh City...");

        Map<String, FoodTag>   tags        = seedTags();
        Map<String, Food>      foods       = seedFoods(tags);
        List<User>             users       = seedUsers();
        List<Restaurant>       restaurants = seedRestaurants(foods);
        seedReviews(users, restaurants, foods);

        log.info("[DataSeeder] Seeding complete — {} restaurants, {} foods, {} tags.",
            restaurants.size(), foods.size(), tags.size());
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Map<String, FoodTag> seedTags() {
        List<FoodTag> saved = foodTagRepository.saveAll(List.of(
            tag("Phở",           "pho"),
            tag("Bún",           "bun"),
            tag("Cơm",           "com"),
            tag("Bánh Mì",       "banh-mi"),
            tag("Lẩu",           "lau"),
            tag("Ăn Vặt",        "an-vat"),
            tag("Điểm Tâm",      "diem-tam"),
            tag("Cay",           "cay"),
            tag("Truyền Thống",  "truyen-thong"),
            tag("Bình Dân",      "binh-dan"),
            tag("Chay",          "chay"),
            tag("Nướng",         "nuong"),
            tag("Miền Nam",      "mien-nam"),
            tag("Hải Sản",       "hai-san"),
            tag("Nhanh",         "nhanh")
        ));
        log.debug("[DataSeeder] Inserted {} tags.", saved.size());
        return saved.stream().collect(Collectors.toMap(FoodTag::getSlug, t -> t));
    }

    // ── Foods ─────────────────────────────────────────────────────────────────

    private Map<String, Food> seedFoods(Map<String, FoodTag> t) {

        Food phoBo = food("Phở Bò Tái", "pho-bo-tai",
            "Phở bò với thịt bò tái hồng mềm, nước dùng ninh từ xương bò trong 12 giờ, " +
            "vị ngọt thanh đặc trưng Sài Gòn. Ăn kèm giá, húng quế và tương hoisin.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1569050467447-ce54b3bbc37d?w=800");
        phoBo.addTag(t.get("pho"));
        phoBo.addTag(t.get("truyen-thong"));
        phoBo.addTag(t.get("diem-tam"));

        Food phoGa = food("Phở Gà", "pho-ga",
            "Phở gà ta thả vườn, nước dùng trong vắt thơm gừng và hành nướng. " +
            "Thịt gà mềm, không mỡ, vị thanh nhẹ hơn phở bò.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1582878826629-29b7ad1cdc43?w=800");
        phoGa.addTag(t.get("pho"));
        phoGa.addTag(t.get("truyen-thong"));
        phoGa.addTag(t.get("diem-tam"));

        Food bunBoHue = food("Bún Bò Huế", "bun-bo-hue",
            "Bún bò đậm đà đặc trưng miền Trung — nước dùng từ mắm ruốc Huế, sả tươi, " +
            "ớt đỏ, và chân giò heo ninh mềm. Mầu nước dùng đỏ cam đặc trưng.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1555126634-323283e090fa?w=800");
        bunBoHue.addTag(t.get("bun"));
        bunBoHue.addTag(t.get("cay"));
        bunBoHue.addTag(t.get("truyen-thong"));

        Food comTam = food("Cơm Tấm Sườn Bì Chả", "com-tam-suon-bi-cha",
            "Cơm tấm đặc sản Sài Gòn với sườn nướng than hoa, bì heo thái mỏng, " +
            "chả trứng hấp mềm, mỡ hành thơm, dưa leo và cà chua tươi.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1565557623262-b51c2513a641?w=800");
        comTam.addTag(t.get("com"));
        comTam.addTag(t.get("mien-nam"));
        comTam.addTag(t.get("nuong"));

        Food banhMi = food("Bánh Mì Thịt Nướng", "banh-mi-thit-nuong",
            "Bánh mì vỏ giòn ruột mềm, kẹp thịt heo nướng than, pate, rau sống và tương ớt đặc biệt. " +
            "Biểu tượng ẩm thực đường phố Việt Nam.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=800");
        banhMi.addTag(t.get("banh-mi"));
        banhMi.addTag(t.get("an-vat"));
        banhMi.addTag(t.get("nhanh"));
        banhMi.addTag(t.get("binh-dan"));

        Food lauMam = food("Lẩu Mắm", "lau-mam",
            "Lẩu mắm cá sông miền Tây — nước lẩu từ mắm cá linh ủ, ăn kèm rau đồng xanh mướt " +
            "như bông súng, bắp chuối bào, và bún tươi. Hải sản topping đa dạng.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=800");
        lauMam.addTag(t.get("lau"));
        lauMam.addTag(t.get("mien-nam"));
        lauMam.addTag(t.get("hai-san"));

        Food huTieu = food("Hủ Tiếu Nam Vang", "hu-tieu-nam-vang",
            "Hủ tiếu dai giòn kiểu Phnom Penh, nước dùng heo trong vắt và ngọt xương, " +
            "ăn kèm thịt heo băm, tôm khô, gan heo và hành phi vàng.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1569050467447-ce54b3bbc37d?w=800");
        huTieu.addTag(t.get("bun"));
        huTieu.addTag(t.get("mien-nam"));
        huTieu.addTag(t.get("diem-tam"));

        Food bunThitNuong = food("Bún Thịt Nướng", "bun-thit-nuong",
            "Bún sợi to ăn kèm thịt heo nướng than hoa, chả giò chiên giòn, " +
            "rau sống đồng nội và nước mắm pha chua ngọt đặc trưng Nam Bộ.",
            "Vietnamese",
            "https://images.unsplash.com/photo-1582878826629-29b7ad1cdc43?w=800");
        bunThitNuong.addTag(t.get("bun"));
        bunThitNuong.addTag(t.get("nuong"));
        bunThitNuong.addTag(t.get("mien-nam"));

        Food goiCuon = food("Gỏi Cuốn Tôm Thịt", "goi-cuon-tom-thit",
            "Gỏi cuốn bánh tráng tươi, nhân tôm luộc hồng tươi, thịt heo ba chỉ, bún, giá đỗ " +
            "và rau thơm — chấm tương hoisin đậu phộng.",
            "Vietnamese", null);
        goiCuon.addTag(t.get("an-vat"));
        goiCuon.addTag(t.get("mien-nam"));
        goiCuon.addTag(t.get("nhanh"));

        Food banhXeo = food("Bánh Xèo", "banh-xeo",
            "Bánh xèo giòn rụm miền Nam — bột gạo nghệ đổ chảo nóng, nhân tôm thịt giá đỗ, " +
            "ăn cuốn bánh tráng với rau sống và chấm nước mắm chua ngọt.",
            "Vietnamese", null);
        banhXeo.addTag(t.get("an-vat"));
        banhXeo.addTag(t.get("mien-nam"));
        banhXeo.addTag(t.get("nuong"));

        Food chaGio = food("Chả Giò", "cha-gio",
            "Chả giò chiên vàng giòn, nhân thịt heo băm, miến, mộc nhĩ và cà rốt — " +
            "chấm nước mắm tỏi ớt hoặc tương hoisin.",
            "Vietnamese", null);
        chaGio.addTag(t.get("an-vat"));
        chaGio.addTag(t.get("truyen-thong"));
        chaGio.addTag(t.get("mien-nam"));

        Food bunMam = food("Bún Mắm", "bun-mam",
            "Bún mắm miền Tây sông nước — nước dùng mắm cá linh đậm đà sánh mịn, " +
            "topping cá thát lát, tôm, mực, heo quay và rau đồng tươi.",
            "Vietnamese", null);
        bunMam.addTag(t.get("bun"));
        bunMam.addTag(t.get("mien-nam"));
        bunMam.addTag(t.get("hai-san"));

        Food chaoLong = food("Cháo Lòng", "chao-long",
            "Cháo trắng nhuyễn nấu cùng lòng heo đa dạng — cật, tim, gan, dồi huyết — " +
            "ăn kèm quẩy giòn và gừng thái sợi.",
            "Vietnamese", null);
        chaoLong.addTag(t.get("diem-tam"));
        chaoLong.addTag(t.get("binh-dan"));
        chaoLong.addTag(t.get("truyen-thong"));

        Food banhCanh = food("Bánh Canh Cua", "banh-canh-cua",
            "Bánh canh bột gạo sợi to dai, nước dùng cua đồng ngọt thanh óng ánh, " +
            "topping cua chắc thịt, chả cá và hành lá.",
            "Vietnamese", null);
        banhCanh.addTag(t.get("bun"));
        banhCanh.addTag(t.get("hai-san"));
        banhCanh.addTag(t.get("mien-nam"));

        Food comChien = food("Cơm Chiên Dương Châu", "com-chien-duong-chau",
            "Cơm chiên phong cách Quảng Đông — trứng, tôm, lạp xưởng, đậu Hà Lan, " +
            "hành lá và dầu hào. Nhanh, no và đậm đà.",
            "Vietnamese-Chinese", null);
        comChien.addTag(t.get("com"));
        comChien.addTag(t.get("nhanh"));
        comChien.addTag(t.get("binh-dan"));

        List<Food> saved = foodRepository.saveAll(List.of(
            phoBo, phoGa, bunBoHue, comTam, banhMi,
            lauMam, huTieu, bunThitNuong, goiCuon, banhXeo,
            chaGio, bunMam, chaoLong, banhCanh, comChien
        ));
        log.debug("[DataSeeder] Inserted {} foods.", saved.size());
        return saved.stream().collect(Collectors.toMap(Food::getSlug, f -> f));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    private List<User> seedUsers() {
        // BCrypt encoding is intentionally done once outside the loop for the shared password.
        // Dev accounts only — the password comes from SEED_DEMO_PASSWORD and is never logged.
        String hash = passwordEncoder.encode(demoPassword);
        log.info("[DataSeeder] Seed user password taken from SEED_DEMO_PASSWORD (app.seed.demo-password).");

        List<User> saved = userRepository.saveAll(List.of(
            user("nguyen.minh.tuan@foodmap.dev", hash, "Nguyễn Minh Tuấn"),
            user("tran.thi.lan@foodmap.dev",     hash, "Trần Thị Lan"),
            user("le.hoang.nam@foodmap.dev",     hash, "Lê Hoàng Nam"),
            user("pham.thi.hoa@foodmap.dev",     hash, "Phạm Thị Hoa"),
            user("dang.van.khoa@foodmap.dev",    hash, "Đặng Văn Khoa")
        ));
        log.debug("[DataSeeder] Inserted {} seed users.", saved.size());
        return saved;
    }

    /** Same rule as registration (RegisterRequest: 8–100 characters). */
    private void requireDemoPassword() {
        if (demoPassword == null || demoPassword.isBlank()
                || demoPassword.length() < 8 || demoPassword.length() > 100) {
            throw new IllegalStateException(
                "[DataSeeder] Seeding an empty database needs a demo account password: "
                    + "set SEED_DEMO_PASSWORD (8–100 characters) for the dev profile.");
        }
    }

    // ── Restaurants ───────────────────────────────────────────────────────────

    private List<Restaurant> seedRestaurants(Map<String, Food> f) {

        // ── 1. Phở Hòa Pasteur — Quận 3 ────────────────────────────────────
        // Landmark phở spot open since 1960, famous for clear bone-broth pho.
        Restaurant phoHoa = restaurant(
            "goong_seed_pho_hoa_pasteur",
            "Phở Hòa Pasteur",
            "260C Pasteur, Phường 8, Quận 3, TP.HCM",
            10.7762, 106.6892,
            "028 3829 7943",
            "Vietnamese", 1,
            Map.of(
                "monday",    "06:00-22:00",
                "tuesday",   "06:00-22:00",
                "wednesday", "06:00-22:00",
                "thursday",  "06:00-22:00",
                "friday",    "06:00-22:30",
                "saturday",  "06:00-22:30",
                "sunday",    "06:00-22:00"
            )
        );
        phoHoa.addFood(rf(phoHoa, f.get("pho-bo-tai"),       bd("75000"), true));
        phoHoa.addFood(rf(phoHoa, f.get("pho-ga"),            bd("70000"), false));
        phoHoa.addFood(rf(phoHoa, f.get("goi-cuon-tom-thit"), bd("45000"), false));

        // ── 2. Bún Bò Huế Bà Hai — Quận 3 ──────────────────────────────────
        Restaurant bunBoHueBaHai = restaurant(
            "goong_seed_bun_bo_hue_ba_hai",
            "Bún Bò Huế Bà Hai",
            "84 Lê Văn Sỹ, Phường 13, Quận 3, TP.HCM",
            10.7847, 106.6824,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "06:00-14:00",
                "tuesday",   "06:00-14:00",
                "wednesday", "06:00-14:00",
                "thursday",  "06:00-14:00",
                "friday",    "06:00-14:00",
                "saturday",  "06:00-14:30",
                "sunday",    "06:00-14:30"
            )
        );
        bunBoHueBaHai.addFood(rf(bunBoHueBaHai, f.get("bun-bo-hue"),    bd("60000"), true));
        bunBoHueBaHai.addFood(rf(bunBoHueBaHai, f.get("cha-gio"),        bd("30000"), false));
        bunBoHueBaHai.addFood(rf(bunBoHueBaHai, f.get("banh-canh-cua"),  bd("65000"), false));

        // ── 3. Cơm Tấm Mộc — Thủ Đức ───────────────────────────────────────
        Restaurant comTamMoc = restaurant(
            "goong_seed_com_tam_moc",
            "Cơm Tấm Mộc",
            "14 Đặng Văn Bi, Phường Bình Thọ, TP Thủ Đức, TP.HCM",
            10.8302, 106.7679,
            "028 3720 0990",
            "Vietnamese", 1,
            Map.of(
                "monday",    "06:30-21:00",
                "tuesday",   "06:30-21:00",
                "wednesday", "06:30-21:00",
                "thursday",  "06:30-21:00",
                "friday",    "06:30-21:00",
                "saturday",  "07:00-21:00",
                "sunday",    "07:00-21:00"
            )
        );
        comTamMoc.addFood(rf(comTamMoc, f.get("com-tam-suon-bi-cha"),  bd("65000"), true));
        comTamMoc.addFood(rf(comTamMoc, f.get("com-chien-duong-chau"), bd("55000"), false));
        comTamMoc.addFood(rf(comTamMoc, f.get("cha-gio"),              bd("25000"), false));
        comTamMoc.addFood(rf(comTamMoc, f.get("goi-cuon-tom-thit"),    bd("40000"), false));

        // ── 4. Bánh Mì Huỳnh Hoa — Quận 1 ──────────────────────────────────
        // Arguably the most famous bánh mì in HCMC — opens at 2pm, queue forms early.
        Restaurant banhMiHuynhHoa = restaurant(
            "goong_seed_banh_mi_huynh_hoa",
            "Bánh Mì Huỳnh Hoa",
            "26 Lê Thị Riêng, Phường Bến Thành, Quận 1, TP.HCM",
            10.7764, 106.7008,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "14:00-23:00",
                "tuesday",   "14:00-23:00",
                "wednesday", "14:00-23:00",
                "thursday",  "14:00-23:00",
                "friday",    "14:00-23:30",
                "saturday",  "14:00-23:30",
                "sunday",    "14:00-23:00"
            )
        );
        banhMiHuynhHoa.addFood(rf(banhMiHuynhHoa, f.get("banh-mi-thit-nuong"), bd("40000"), true));
        banhMiHuynhHoa.addFood(rf(banhMiHuynhHoa, f.get("goi-cuon-tom-thit"),  bd("45000"), false));
        banhMiHuynhHoa.addFood(rf(banhMiHuynhHoa, f.get("cha-gio"),            bd("35000"), false));

        // ── 5. Lẩu Mắm Ngọc Giàu — Bình Thạnh ──────────────────────────────
        Restaurant lauMamNgocGiau = restaurant(
            "goong_seed_lau_mam_ngoc_giau",
            "Lẩu Mắm Ngọc Giàu",
            "123 Đinh Tiên Hoàng, Phường 3, Quận Bình Thạnh, TP.HCM",
            10.8102, 106.7083,
            "028 3840 2211",
            "Vietnamese", 2,
            Map.of(
                "monday",    "10:00-22:00",
                "tuesday",   "10:00-22:00",
                "wednesday", "10:00-22:00",
                "thursday",  "10:00-22:00",
                "friday",    "10:00-22:30",
                "saturday",  "10:00-22:30",
                "sunday",    "10:00-22:00"
            )
        );
        lauMamNgocGiau.addFood(rf(lauMamNgocGiau, f.get("lau-mam"),          bd("320000"), true));
        lauMamNgocGiau.addFood(rf(lauMamNgocGiau, f.get("bun-mam"),           bd("70000"),  false));
        lauMamNgocGiau.addFood(rf(lauMamNgocGiau, f.get("banh-xeo"),          bd("60000"),  false));
        lauMamNgocGiau.addFood(rf(lauMamNgocGiau, f.get("goi-cuon-tom-thit"), bd("50000"),  false));

        // ── 6. Hủ Tiếu Nam Vang Bà Cả — Quận 10 ────────────────────────────
        Restaurant huTieuBaCa = restaurant(
            "goong_seed_hu_tieu_ba_ca",
            "Hủ Tiếu Nam Vang Bà Cả",
            "245 Sư Vạn Hạnh, Phường 9, Quận 10, TP.HCM",
            10.7738, 106.6678,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "05:30-13:00",
                "tuesday",   "05:30-13:00",
                "wednesday", "05:30-13:00",
                "thursday",  "05:30-13:00",
                "friday",    "05:30-13:30",
                "saturday",  "05:30-14:00",
                "sunday",    "05:30-14:00"
            )
        );
        huTieuBaCa.addFood(rf(huTieuBaCa, f.get("hu-tieu-nam-vang"), bd("55000"), true));
        huTieuBaCa.addFood(rf(huTieuBaCa, f.get("chao-long"),        bd("45000"), false));
        huTieuBaCa.addFood(rf(huTieuBaCa, f.get("banh-canh-cua"),    bd("65000"), false));

        // ── 7. Bún Thịt Nướng Cô Ba — Phú Nhuận ────────────────────────────
        Restaurant bunThitNuongCoBa = restaurant(
            "goong_seed_bun_thit_nuong_co_ba",
            "Bún Thịt Nướng Cô Ba",
            "37 Phan Đình Phùng, Phường 2, Quận Phú Nhuận, TP.HCM",
            10.7964, 106.6814,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "10:00-21:00",
                "tuesday",   "10:00-21:00",
                "wednesday", "10:00-21:00",
                "thursday",  "10:00-21:00",
                "friday",    "10:00-21:30",
                "saturday",  "10:00-21:30",
                "sunday",    "10:00-21:00"
            )
        );
        bunThitNuongCoBa.addFood(rf(bunThitNuongCoBa, f.get("bun-thit-nuong"),    bd("55000"), true));
        bunThitNuongCoBa.addFood(rf(bunThitNuongCoBa, f.get("cha-gio"),           bd("35000"), false));
        bunThitNuongCoBa.addFood(rf(bunThitNuongCoBa, f.get("banh-xeo"),          bd("60000"), false));
        bunThitNuongCoBa.addFood(rf(bunThitNuongCoBa, f.get("goi-cuon-tom-thit"), bd("45000"), false));

        // ── 8. Cháo Lòng Thanh Xuân — Gò Vấp ───────────────────────────────
        Restaurant chaoLongThanhXuan = restaurant(
            "goong_seed_chao_long_thanh_xuan",
            "Cháo Lòng Thanh Xuân",
            "182 Nguyễn Văn Nghi, Phường 7, Quận Gò Vấp, TP.HCM",
            10.8341, 106.6653,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "05:00-12:00",
                "tuesday",   "05:00-12:00",
                "wednesday", "05:00-12:00",
                "thursday",  "05:00-12:00",
                "friday",    "05:00-12:00",
                "saturday",  "05:00-12:30",
                "sunday",    "05:00-12:30"
            )
        );
        chaoLongThanhXuan.addFood(rf(chaoLongThanhXuan, f.get("chao-long"),           bd("45000"), true));
        chaoLongThanhXuan.addFood(rf(chaoLongThanhXuan, f.get("hu-tieu-nam-vang"),    bd("50000"), false));
        chaoLongThanhXuan.addFood(rf(chaoLongThanhXuan, f.get("com-chien-duong-chau"), bd("50000"), false));

        // ── 9. Nhà Hàng Ngon — Quận 1 (upscale, tourist-friendly) ───────────
        Restaurant nhaHangNgon = restaurant(
            "goong_seed_nha_hang_ngon",
            "Nhà Hàng Ngon",
            "160 Pasteur, Phường Võ Thị Sáu, Quận 3, TP.HCM",
            10.7779, 106.6952,
            "028 3827 7131",
            "Vietnamese", 2,
            Map.of(
                "monday",    "07:00-22:00",
                "tuesday",   "07:00-22:00",
                "wednesday", "07:00-22:00",
                "thursday",  "07:00-22:00",
                "friday",    "07:00-22:30",
                "saturday",  "07:00-22:30",
                "sunday",    "07:00-22:00"
            )
        );
        nhaHangNgon.addFood(rf(nhaHangNgon, f.get("banh-xeo"),           bd("95000"),  true));
        nhaHangNgon.addFood(rf(nhaHangNgon, f.get("goi-cuon-tom-thit"),  bd("75000"),  false));
        nhaHangNgon.addFood(rf(nhaHangNgon, f.get("bun-thit-nuong"),     bd("95000"),  false));
        nhaHangNgon.addFood(rf(nhaHangNgon, f.get("cha-gio"),            bd("75000"),  false));
        nhaHangNgon.addFood(rf(nhaHangNgon, f.get("pho-bo-tai"),         bd("125000"), false));

        // ── 10. Quán Cơm Bà Cụ — Quận 7 ────────────────────────────────────
        Restaurant quanComBaCu = restaurant(
            "goong_seed_quan_com_ba_cu",
            "Quán Cơm Bà Cụ",
            "45 Nguyễn Thị Thập, Phường Tân Phú, Quận 7, TP.HCM",
            10.7325, 106.7101,
            null,
            "Vietnamese", 1,
            Map.of(
                "monday",    "09:00-20:30",
                "tuesday",   "09:00-20:30",
                "wednesday", "09:00-20:30",
                "thursday",  "09:00-20:30",
                "friday",    "09:00-21:00",
                "saturday",  "09:00-21:00",
                "sunday",    "10:00-20:00"
            )
        );
        quanComBaCu.addFood(rf(quanComBaCu, f.get("com-tam-suon-bi-cha"),  bd("60000"), true));
        quanComBaCu.addFood(rf(quanComBaCu, f.get("com-chien-duong-chau"), bd("55000"), false));
        quanComBaCu.addFood(rf(quanComBaCu, f.get("banh-canh-cua"),        bd("60000"), false));

        List<Restaurant> saved = restaurantRepository.saveAll(List.of(
            phoHoa, bunBoHueBaHai, comTamMoc, banhMiHuynhHoa, lauMamNgocGiau,
            huTieuBaCa, bunThitNuongCoBa, chaoLongThanhXuan, nhaHangNgon, quanComBaCu
        ));
        log.debug("[DataSeeder] Inserted {} restaurants with cascaded menu items.", saved.size());
        return saved;
    }

    // ── Reviews ───────────────────────────────────────────────────────────────

    private void seedReviews(List<User> u, List<Restaurant> r, Map<String, Food> f) {
        User tuan = u.get(0), lan = u.get(1), nam = u.get(2), hoa = u.get(3), khoa = u.get(4);

        List<Review> reviews = List.of(

            // ── Phở Hòa Pasteur ─────────────────────────────────────────────
            review(tuan, r.get(0), f.get("pho-bo-tai"), 5,
                "Phở Hòa là chuẩn phở Sài Gòn. Nước dùng trong vắt, ngọt xương, " +
                "tái mềm tan trong miệng. Ghé sớm 6h để tránh đông!"),
            review(lan, r.get(0), f.get("pho-bo-tai"), 5,
                "Ăn ở đây hơn 10 năm rồi, chất lượng không đổi. Tô phở bò tái đặc biệt đúng là đỉnh."),
            review(nam, r.get(0), null, 4,
                "Quán đông khách, phục vụ hơi chậm buổi sáng nhưng chất lượng xứng đáng chờ đợi."),
            review(hoa, r.get(0), f.get("pho-ga"), 4,
                "Phở gà nước trong, thơm gừng, thịt mềm đúng vị. Không quá mỡ như nhiều quán khác."),

            // ── Bún Bò Huế Bà Hai ───────────────────────────────────────────
            review(tuan, r.get(1), f.get("bun-bo-hue"), 5,
                "Bún bò Bà Hai cay và thơm sả đúng vị Huế. Chân giò heo mềm, không bị bở. Top 1 Sài Gòn!"),
            review(nam, r.get(1), f.get("bun-bo-hue"), 4,
                "Nước dùng đậm đà, nhiều mắm ruốc Huế hơn các quán bình thường. Hơi cay nhưng ngon."),
            review(khoa, r.get(1), null, 4,
                "Quán nhỏ nhưng đông khách, không khí thân thiện. Giá bình dân cho chất lượng này là ổn."),

            // ── Cơm Tấm Mộc ─────────────────────────────────────────────────
            review(lan, r.get(2), f.get("com-tam-suon-bi-cha"), 5,
                "Sườn nướng than hoa thơm ngào ngạt, bì heo mỏng giòn, chả hấp mềm. Cơm tấm ngon nhất Thủ Đức!"),
            review(hoa, r.get(2), f.get("com-tam-suon-bi-cha"), 4,
                "Cơm tấm ngon, phần ăn đầy đặn. Mỡ hành nhiều, nước mắm pha chuẩn vị Nam."),
            review(khoa, r.get(2), null, 5,
                "Đây là quán cơm tấm tui hay ghé nhất. Giá cả hợp lý, phục vụ nhanh, chất lượng ổn định."),

            // ── Bánh Mì Huỳnh Hoa ───────────────────────────────────────────
            review(tuan, r.get(3), f.get("banh-mi-thit-nuong"), 5,
                "Bánh mì Huỳnh Hoa xứng đáng là huyền thoại. Vỏ siêu giòn, nhân thịt đầy ắp. " +
                "Phải xếp hàng nhưng hoàn toàn worth it!"),
            review(lan, r.get(3), f.get("banh-mi-thit-nuong"), 5,
                "Nhiều nhân nhất Sài Gòn, giá 40k mà no căng bụng. Buổi chiều 2h mới bán, nhớ đến đúng giờ!"),
            review(nam, r.get(3), null, 4,
                "Xếp hàng 20 phút nhưng hoàn toàn hài lòng. Ngon hơn nhiều quán nổi tiếng khác."),

            // ── Lẩu Mắm Ngọc Giàu ───────────────────────────────────────────
            review(lan, r.get(4), f.get("lau-mam"), 5,
                "Lẩu mắm ở đây chất lượng nhà hàng nhưng giá quán bình dân. " +
                "Nước dùng đậm đà, rau đồng tươi, hải sản nhiều."),
            review(hoa, r.get(4), f.get("bun-mam"), 4,
                "Bún mắm cũng ngon, nước dùng đặc sánh, vị mắm đậm đúng kiểu miền Tây sông nước."),
            review(khoa, r.get(4), f.get("banh-xeo"), 5,
                "Bánh xèo giòn tan, nhân tôm thịt đầy đủ. Ăn cùng lẩu mắm là combo tuyệt vời."),

            // ── Hủ Tiếu Nam Vang Bà Cả ──────────────────────────────────────
            review(nam, r.get(5), f.get("hu-tieu-nam-vang"), 5,
                "Hủ tiếu sợi dai giòn đúng chuẩn Nam Vang, nước dùng heo trong và ngọt thanh. Ghé từ 5:30 sáng!"),
            review(tuan, r.get(5), f.get("hu-tieu-nam-vang"), 4,
                "Topping đa dạng: thịt băm, tôm khô, gan heo đều tươi. Giá bình dân cho một tô đầy đặn."),
            review(hoa, r.get(5), null, 4,
                "Quán gia đình mấy chục năm, vị không thay đổi. Điểm tâm buổi sáng lý tưởng ở quận 10."),

            // ── Bún Thịt Nướng Cô Ba ────────────────────────────────────────
            review(khoa, r.get(6), f.get("bun-thit-nuong"), 5,
                "Bún thịt nướng ngon nhất Phú Nhuận! Thịt nướng than hoa thơm, bún tươi, nước mắm chuẩn vị."),
            review(lan, r.get(6), f.get("bun-thit-nuong"), 4,
                "Chả giò ăn kèm siêu giòn, thịt nướng đủ lửa. Phần ăn vừa phải, giá hợp lý."),
            review(nam, r.get(6), f.get("banh-xeo"), 5,
                "Bánh xèo vỏ giòn vàng ươm, nhân tươi, ăn cuốn bánh tráng rau sống rất ngon."),

            // ── Cháo Lòng Thanh Xuân ────────────────────────────────────────
            review(hoa, r.get(7), f.get("chao-long"), 5,
                "Cháo trắng nấu nhuyễn, lòng heo sạch và đa dạng: tim, cật, gan, dồi huyết đều có. " +
                "Ăn sáng sớm 5h là ngon nhất."),
            review(tuan, r.get(7), f.get("chao-long"), 4,
                "Quán mở rất sớm, phù hợp cho người dậy sớm. Cháo đặc nóng, quẩy giòn ăn kèm rất đã."),
            review(khoa, r.get(7), null, 4,
                "Giá bình dân, chất lượng tốt cho khu Gò Vấp. Phục vụ nhanh, sạch sẽ."),

            // ── Nhà Hàng Ngon ────────────────────────────────────────────────
            review(lan, r.get(8), f.get("banh-xeo"), 5,
                "Bánh xèo ở Nhà Hàng Ngon giòn đều, nhân đầy đủ, trình bày đẹp. " +
                "Không gian thoáng mát, phù hợp tiếp khách nước ngoài."),
            review(nam, r.get(8), f.get("goi-cuon-tom-thit"), 4,
                "Gỏi cuốn tươi ngon, tôm to đỏ hồng, nhân đầy đặn. Giá cao hơn bình dân nhưng chất lượng xứng đáng."),
            review(hoa, r.get(8), null, 4,
                "Nhà hàng đẹp, nhiều món ăn truyền thống trong một menu. Phù hợp cho gia đình hoặc tiếp khách."),

            // ── Quán Cơm Bà Cụ ──────────────────────────────────────────────
            review(khoa, r.get(9), f.get("com-tam-suon-bi-cha"), 4,
                "Cơm tấm ở Quận 7 ngon, sườn nướng mềm thơm. Quán nhỏ, hay hết sớm nên nhớ đến trước 12h trưa."),
            review(tuan, r.get(9), f.get("banh-canh-cua"), 4,
                "Bánh canh cua nước dùng ngọt cua thật, không pha bột ngọt nhiều. Hiếm quán nào làm đúng vị như vậy."),
            review(nam, r.get(9), null, 3,
                "Quán ổn, đồ ăn bình dân. Cơm tấm được nhưng không xuất sắc bằng các quán chuyên biệt.")
        );

        // Flush reviews to DB before running the aggregate UPDATE query.
        // Without this, Hibernate may not have written the rows yet when the
        // correlated AVG subquery inside recalculateRatingStats executes.
        reviewRepository.saveAllAndFlush(reviews);
        log.debug("[DataSeeder] Inserted {} reviews.", reviews.size());

        for (Restaurant restaurant : r) {
            restaurantRepository.recalculateRatingStats(restaurant.getId());
        }
        log.debug("[DataSeeder] Rating stats recalculated for {} restaurants.", r.size());
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private FoodTag tag(String name, String slug) {
        return FoodTag.builder().name(name).slug(slug).build();
    }

    private Food food(String name, String slug, String description, String cuisine, String photoUrl) {
        return Food.builder()
            .name(name)
            .slug(slug)
            .description(description)
            .cuisine(cuisine)
            .photoUrl(photoUrl)
            .build();
    }

    private User user(String email, String passwordHash, String displayName) {
        return User.builder()
            .email(email)
            .passwordHash(passwordHash)
            .displayName(displayName)
            .role(Role.USER)
            .enabled(true)
            .build();
    }

    private Restaurant restaurant(String goongId, String name, String address,
                                   double lat, double lng, String phone,
                                   String cuisineType, int priceLevel,
                                   Map<String, String> hours) {
        return Restaurant.builder()
            .goongPlaceId(goongId)
            .name(name)
            .address(address)
            .latitude(lat)
            .longitude(lng)
            .phone(phone)
            .cuisineType(cuisineType)
            .priceLevel(priceLevel)
            .openingHours(hours)
            .isActive(true)
            .lastSyncedAt(Instant.now())
            .build();
    }

    private RestaurantFood rf(Restaurant restaurant, Food food, BigDecimal price, boolean signature) {
        return RestaurantFood.builder()
            .restaurant(restaurant)
            .food(food)
            .price(price)
            .isSignature(signature)
            .build();
    }

    private Review review(User user, Restaurant restaurant, Food food, int rating, String comment) {
        return Review.builder()
            .user(user)
            .restaurant(restaurant)
            .food(food)
            .rating(rating)
            .comment(comment)
            .build();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
