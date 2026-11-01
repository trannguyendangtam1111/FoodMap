package com.foodmap.decision;

import com.foodmap.decision.service.Mood;
import com.foodmap.decision.service.MoodMatcher;
import com.foodmap.decision.service.MoodMatcher.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "mon-nuoc" matching against real FoodMap names and menu dishes. */
class MoodMatcherTest {

    @ParameterizedTest(name = "\"{0}\" fits món nước by name")
    @ValueSource(strings = {
        "Phở Hòa Pasteur", "Bún Bò Huế Bà Hai", "Hủ Tiếu Nam Vang Bà Cả",
        "Quán 510 - Bún bò, hủ híu", "Bún bò, bún riêu Cô Hồng", "Pho 24", "bun bo hue"
    })
    void nameMatches(String name) {
        assertThat(MoodMatcher.evidence(Mood.MON_NUOC, name, List.of()))
            .hasValueSatisfying(e -> assertThat(e.source()).isEqualTo(Source.NAME));
    }

    @ParameterizedTest(name = "\"{0}\" does not fit món nước")
    @ValueSource(strings = {
        "Bánh Mì Huỳnh Hoa",       // "mì" inside "bánh mì" must not count
        "Bún Thịt Nướng Cô Ba",    // dry bún
        "Bún Đậu Ngõ Nhỏ Phố Nhỏ", // dry bún; "Phố" (street) is not "phở"
        "Cafe Phố",
        "Lẩu Mắm Ngọc Giàu",       // lẩu is its own category
        "Cháo Lòng Thanh Xuân",
        "Restaurant Blue Sky",
        "Cửa hàng Điện Thoại Di Động Huy Phong",
        "Mien Tay Restaurant"      // ascii "mien" is ambiguous (miền/miến) → not matched
    })
    void nameDoesNotMatch(String name) {
        assertThat(MoodMatcher.evidence(Mood.MON_NUOC, name, List.of())).isEmpty();
    }

    @Test
    void menuDish_isEvidence_withDishName() {
        // Real seeded case: "Quán Cơm Bà Cụ" serves Bánh Canh Cua
        var evidence = MoodMatcher.evidence(Mood.MON_NUOC, "Quán Cơm Bà Cụ",
            List.of("Cơm Tấm Sườn Bì Chả", "Cơm Chiên Dương Châu", "Bánh Canh Cua"));
        assertThat(evidence).hasValueSatisfying(e -> {
            assertThat(e.source()).isEqualTo(Source.MENU);
            assertThat(e.dishName()).isEqualTo("Bánh Canh Cua");
        });
    }

    @Test
    void nameEvidence_winsOverMenu() {
        var evidence = MoodMatcher.evidence(Mood.MON_NUOC, "Phở Hòa", List.of("Bún Bò Huế"));
        assertThat(evidence).hasValueSatisfying(e -> assertThat(e.source()).isEqualTo(Source.NAME));
    }

    @Test
    void menuWithoutSoup_isNotEvidence() {
        assertThat(MoodMatcher.evidence(Mood.MON_NUOC, "Bánh Mì Huỳnh Hoa",
            List.of("Bánh Mì Thịt Nướng", "Gỏi Cuốn Tôm Thịt", "Chả Giò"))).isEmpty();
    }

    @Test
    void nullAndBlankInputs_areSafe() {
        assertThat(MoodMatcher.evidence(Mood.MON_NUOC, null, List.of())).isEmpty();
        assertThat(MoodMatcher.evidence(Mood.MON_NUOC, "  ", List.of(" "))).isEmpty();
    }
}
