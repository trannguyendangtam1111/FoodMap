package com.foodmap.config;

import com.foodmap.security.JwtAuthenticationFilter;
import com.foodmap.security.JwtAccessDeniedHandler;
import com.foodmap.security.JwtAuthenticationEntryPoint;
import com.foodmap.security.JwtTokenProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mock web context only: no datasource, application scanning, seeder, or provider client. */
@SpringBootTest(classes = RenderDeploymentTest.WebConfig.class, properties = {
    "spring.config.location=classpath:/application-prod.yml",
    "spring.profiles.active=prod",
    "dotenv.enabled=false",
    "PORT=12345",
    "CORS_ALLOWED_ORIGINS=https://foodmap.example, https://preview.example, "
})
@AutoConfigureMockMvc
class RenderDeploymentTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
    })
    @Import({SecurityConfig.class, ProductionFlywayConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
    static class WebConfig {}

    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserDetailsService userDetailsService;
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired FlywayMigrationStrategy flywayStrategy;

    @Test
    void productionDefaultsUseRenderPortAndConservativeDatabaseSettings() {
        assertThat(environment.getActiveProfiles()).containsExactly("prod");
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(12345);
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class)).isZero();
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode")).isEqualTo("require");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class)).isTrue();
        for (String trigger : new String[]{"seed-on-startup", "viewport-enqueue-enabled", "scheduled-recrawl-enabled"}) {
            assertThat(environment.getProperty("app.ingestion." + trigger, Boolean.class)).isFalse();
        }
    }

    @Test
    void productionFlywayStrategyOnlyValidatesHistory() {
        Flyway flyway = mock(Flyway.class);
        flywayStrategy.migrate(flyway);
        verify(flyway).validate();
        verify(flyway, never()).migrate();
        verify(flyway, never()).baseline();
        verify(flyway, never()).clean();
    }

    @Test
    void livenessIsPublicWithoutDetailsAndOtherManagementPathsAreProtected() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void corsTrimsConfiguredOriginsAndRejectsUnlistedOrigins() throws Exception {
        mvc.perform(options("/api/v1/restaurants/search")
                .header("Origin", "https://preview.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://preview.example"));
        mvc.perform(options("/api/v1/restaurants/search")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
