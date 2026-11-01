package com.foodmap.restaurant.repository;

import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.dto.SearchCandidateRow;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Container-only persistence tests: no Spring Boot context, dotenv, seeder, or provider beans. */
@Testcontainers
class RestaurantRepositoryPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("foodmap_it")
        .withUsername("foodmap_it")
        .withPassword("disposable-test-password");

    private static Flyway flyway;
    private static JdbcTemplate jdbc;
    private static LocalContainerEntityManagerFactoryBean factory;

    private EntityManager entityManager;
    private RestaurantRepository repository;

    @BeforeAll
    static void migrateFreshContainerDatabase() {
        var dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // The PostGIS image preinstalls extensions. Empty only this disposable schema
        // so Flyway must create the application schema AND its required extensions.
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .load();
        flyway.migrate();

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.foodmap");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        factory.afterPropertiesSet();
    }

    @BeforeEach
    void createRepository() {
        entityManager = factory.getObject().createEntityManager();
        repository = new JpaRepositoryFactory(entityManager).getRepository(RestaurantRepository.class);
    }

    @AfterEach
    void clearFixtures() {
        if (entityManager != null) entityManager.close();
        jdbc.execute("TRUNCATE TABLE restaurants, foods RESTART IDENTITY CASCADE");
    }

    @AfterAll
    static void closePersistence() {
        if (factory != null) factory.destroy();
    }

    @Test
    void appliesAllMigrationsAndValidatesTheFreshSchema() {
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied())
            .extracting(migration -> migration.getVersion().getVersion())
            .contains("1", "9");
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
            .contains("postgis", "pg_trgm", "unaccent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Long.class)).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void radiusQueryIncludesJustInsideAndExcludesJustOutsideAndInactivePlaces() {
        long centre = insertAtDistance("Centre", 0, true);
        long inside = insertAtDistance("Inside", 999.9, true);
        insertAtDistance("Outside", 1000.1, true);
        insertAtDistance("Inactive", 500, false);

        // Both boundary fixtures are within this box, so ST_DWithin decides membership.
        List<AvailabilityCandidateRow> rows = repository.findAvailabilityCandidates(
            10.7769, 106.7009, 1000, 10.75, 106.67, 10.80, 106.73, 20);

        assertThat(rows).extracting(AvailabilityCandidateRow::getId).containsExactly(centre, inside);
        assertThat(rows.get(0).getDistanceMeters()).isCloseTo(0, within(0.01));
        assertThat(rows.get(1).getDistanceMeters()).isCloseTo(999.9, within(0.01));
    }

    @Test
    void searchQueryFindsAccentFoldedNameFullTextAddressAndMenuButNotInactivePlaces() {
        long name = insertAtDistance("Ph\u1edf H\u00f2a", 0, true);
        long address = insertAtDistance("Address Match", 100, true);
        jdbc.update("UPDATE restaurants SET address = ? WHERE id = ?", "Pasteur District", address);
        long menu = insertAtDistance("Menu Match", 200, true);
        Long food = jdbc.queryForObject(
            "INSERT INTO foods (name, slug) VALUES (?, ?) RETURNING id",
            Long.class, "Ph\u1edf b\u00f2", "pho-bo-it");
        jdbc.update("INSERT INTO restaurant_foods (restaurant_id, food_id) VALUES (?, ?)", menu, food);
        insertAtDistance("Ph\u1edf Inactive", 300, false);
        insertAtDistance("Unrelated", 400, true);

        List<SearchCandidateRow> rows = repository.findSearchCandidates("pho", "%pho%", "pho%", 20);
        assertThat(rows).extracting(SearchCandidateRow::getId).containsExactly(name, menu);
        assertThat(rows.get(0).getName()).isEqualTo("Ph\u1edf H\u00f2a");
        assertThat(rows.get(0).getNameSimilarity()).isGreaterThan(0);

        // Reversed words match only through full-text search, not the literal LIKE branches.
        assertThat(repository.findSearchCandidates("district pasteur", "%district pasteur%", "district pasteur%", 20))
            .extracting(SearchCandidateRow::getId).containsExactly(address);
    }

    private long insertAtDistance(String name, double metres, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO restaurants (goong_place_id, name, latitude, longitude, is_active)
            SELECT ?, ?, ST_Y(point), ST_X(point), ?
            FROM (
                SELECT CAST(ST_Project(
                    CAST(ST_SetSRID(ST_MakePoint(106.7009, 10.7769), 4326) AS geography),
                    ?, radians(90)) AS geometry) AS point
            ) projected
            RETURNING id
            """, Long.class, "it-" + name, name, active, metres);
    }
}
