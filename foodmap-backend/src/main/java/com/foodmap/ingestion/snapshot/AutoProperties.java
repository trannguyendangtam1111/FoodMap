package com.foodmap.ingestion.snapshot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Options of an AUTO snapshot run (app.snapshot.auto=true), e.g.
 *
 *   --app.snapshot.auto.max-probes=20 --app.snapshot.auto.areas=quan-3,phu-nhuan
 *   --app.snapshot.auto.exclude-foods=mi,nuong,chay,ca-phe,tra-sua,kem
 *   --app.snapshot.auto.target=3 --app.snapshot.auto.min-score=0.25 --app.snapshot.auto.wave-size=6
 *
 * The budget, execute flag, daily ceiling and report directory are the
 * shared app.snapshot.* settings.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.snapshot.auto")
public class AutoProperties {

    public static final int MAX_WAVE_SIZE = 6;
    public static final int MAX_PROBES_LIMIT = 50;

    /** Probes (Autocomplete queries) per run, resumed pairs not counted. 1..50. */
    private int maxProbes = 20;

    /** Area preset keys AUTO may probe; empty = all presets. Neighbours outside it still count for overlap. */
    private List<String> areas = new ArrayList<>();

    /** Foods never probed automatically (catalog keys). */
    private List<String> excludeFoods = new ArrayList<>(List.of("mi", "nuong", "chay", "ca-phe", "tra-sua", "kem"));

    /** Coverage target per food × area (restaurants inside the area's runtime radius). 1..20. */
    private int target = 3;

    /** Probes expected to yield fewer useful restaurants per Goong request are not run. */
    private double minScore = 0.25;

    /** Probes per wave (one area per wave). 1..6. */
    private int waveSize = MAX_WAVE_SIZE;

    /** The only database AUTO may write to: host:port/database of the JDBC URL. */
    private String allowedDb = "localhost:5434/foodmap_dev";
}
