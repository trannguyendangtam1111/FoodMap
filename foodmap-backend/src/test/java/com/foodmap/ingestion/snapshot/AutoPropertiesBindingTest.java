package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The CLI flags: app.snapshot.auto=true next to app.snapshot.auto.* options. */
class AutoPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties({SnapshotProperties.class, AutoProperties.class})
    static class Props {
    }

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Test
    void theFlagAndItsOptionsBindTogether() {
        runner.withPropertyValues("app.snapshot.auto=true", "app.snapshot.budget=40", "app.snapshot.large=true",
                "app.snapshot.auto.max-probes=5", "app.snapshot.auto.areas=quan-3,phu-nhuan",
                "app.snapshot.auto.exclude-foods=mi,kem", "app.snapshot.auto.target=4",
                "app.snapshot.auto.min-score=0.3", "app.snapshot.auto.wave-size=3",
                "app.snapshot.auto.allowed-db=localhost:5434/foodmap_test")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                SnapshotProperties p = ctx.getBean(SnapshotProperties.class);
                assertThat(p.isAuto()).isTrue();
                assertThat(p.isExecute()).isFalse();   // dry run unless execute=true
                assertThat(p.getBudget()).isEqualTo(40);
                AutoProperties a = ctx.getBean(AutoProperties.class);
                assertThat(a.getMaxProbes()).isEqualTo(5);
                assertThat(a.getAreas()).containsExactly("quan-3", "phu-nhuan");
                assertThat(a.getExcludeFoods()).containsExactly("mi", "kem");
                assertThat(a.getTarget()).isEqualTo(4);
                assertThat(a.getMinScore()).isEqualTo(0.3);
                assertThat(a.getWaveSize()).isEqualTo(3);
                assertThat(a.getAllowedDb()).isEqualTo("localhost:5434/foodmap_test");
            });
    }

    @Test
    void theApprovedDefaults() {
        runner.withPropertyValues("app.snapshot.auto=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SnapshotProperties.class).isAuto()).isTrue();
            assertThat(ctx.getBean(SnapshotProperties.class).getDailyCeiling()).isEqualTo(120);
            AutoProperties a = ctx.getBean(AutoProperties.class);
            assertThat(a.getMaxProbes()).isEqualTo(20);
            assertThat(a.getAreas()).isEmpty();
            assertThat(a.getExcludeFoods()).containsExactly("mi", "nuong", "chay", "ca-phe", "tra-sua", "kem");
            assertThat(a.getTarget()).isEqualTo(3);
            assertThat(a.getMinScore()).isEqualTo(0.25);
            assertThat(a.getWaveSize()).isEqualTo(6);
            assertThat(a.getAllowedDb()).isEqualTo("localhost:5434/foodmap_dev");
        });
        runner.run(ctx -> assertThat(ctx.getBean(SnapshotProperties.class).isAuto()).isFalse());
        assertThat(List.of()).isEmpty();
    }
}
