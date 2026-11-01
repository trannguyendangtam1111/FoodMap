package com.foodmap.security;

import com.foodmap.config.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JWT secret is checked at startup by measuring the UTF-8 bytes that JJWT
 * actually uses as the HS256 key. These tests pin that contract.
 */
class JwtSecretValidatorTest {

    /** Same length as the weak root .env value found in the V2 audit (19 bytes). */
    private static final String WEAK_19 = "weak-secret-19-byte";

    private static String ascii(int bytes) {
        return "s".repeat(bytes);
    }

    @Test
    void rejects31Bytes_andReportsLengthWithoutTheSecret() {
        String secret = "k".repeat(31);
        assertThatThrownBy(() -> JwtSecretValidator.toSigningKey(secret))
            .isInstanceOf(InvalidJwtSecretException.class)
            .hasMessageContaining("31 bytes")
            .hasMessageContaining("minimum 32 bytes")
            .hasMessageContaining("openssl rand -base64 48")
            .message().doesNotContain(secret);
    }

    @Test
    void accepts32Bytes() {
        assertThatCode(() -> JwtSecretValidator.toSigningKey(ascii(32))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheKnownWeak19ByteSecret() {
        assertThat(WEAK_19).hasSize(19);
        assertThatThrownBy(() -> JwtSecretValidator.toSigningKey(WEAK_19))
            .isInstanceOf(InvalidJwtSecretException.class)
            .hasMessageContaining("19 bytes")
            .message().doesNotContain(WEAK_19);
    }

    @Test
    void countsUtf8Bytes_notCharacters() {
        // "é" is 2 bytes in UTF-8: 16 chars = 32 bytes → accepted
        String sixteenChars32Bytes = "é".repeat(16);
        assertThat(sixteenChars32Bytes).hasSize(16);
        assertThat(JwtSecretValidator.byteLength(sixteenChars32Bytes)).isEqualTo(32);
        assertThatCode(() -> JwtSecretValidator.toSigningKey(sixteenChars32Bytes)).doesNotThrowAnyException();

        // 16 chars but only 31 bytes → rejected, and reported in bytes
        String sixteenChars31Bytes = "é".repeat(15) + "a";
        assertThat(sixteenChars31Bytes).hasSize(16);
        assertThatThrownBy(() -> JwtSecretValidator.toSigningKey(sixteenChars31Bytes))
            .isInstanceOf(InvalidJwtSecretException.class)
            .hasMessageContaining("31 bytes");
    }

    @Test
    void rejectsMissingOrBlankSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.toSigningKey(null))
            .isInstanceOf(InvalidJwtSecretException.class).hasMessageContaining("not set");
        assertThatThrownBy(() -> JwtSecretValidator.toSigningKey("   "))
            .isInstanceOf(InvalidJwtSecretException.class).hasMessageContaining("not set");
    }

    @Test
    void tokenProvider_failsWhenConstructed_notOnFirstToken() {
        JwtProperties props = new JwtProperties();
        props.setSecret(WEAK_19);
        props.setAccessTokenExpirationMs(3_600_000L);
        props.setIssuer("foodmap-test");

        assertThatThrownBy(() -> new JwtTokenProvider(props))
            .isInstanceOf(InvalidJwtSecretException.class);
    }

    // ── Startup behaviour ─────────────────────────────────────────────────────

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    @Import(JwtTokenProvider.class)
    static class JwtOnlyConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(JwtOnlyConfig.class)
        .withPropertyValues("app.jwt.access-token-expiration-ms=3600000", "app.jwt.issuer=foodmap-test");

    @Test
    void weakSecret_preventsContextStartup() {
        runner.withPropertyValues("app.jwt.secret=" + WEAK_19).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(InvalidJwtSecretException.class)
                .hasMessageContaining("19 bytes");
        });
    }

    @Test
    void strongSecret_startsContext() {
        runner.withPropertyValues("app.jwt.secret=" + ascii(48)).run(context ->
            assertThat(context).hasNotFailed().hasSingleBean(JwtTokenProvider.class));
    }

    @Test
    void failureAnalyzer_explainsWithoutPrintingTheSecret() {
        runner.withPropertyValues("app.jwt.secret=" + WEAK_19).run(context -> {
            FailureAnalysis analysis = new InvalidJwtSecretFailureAnalyzer().analyze(context.getStartupFailure());
            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains("19 bytes").doesNotContain(WEAK_19);
            assertThat(analysis.getAction()).contains("openssl rand -base64 48").doesNotContain(WEAK_19);
        });
    }

    @Test
    void prodGuard_rejectsTheEnvExamplePlaceholder() {
        assertThatThrownBy(() -> ProdJwtSecretGuard.check(ProdJwtSecretGuard.EXAMPLE_PLACEHOLDER))
            .isInstanceOf(InvalidJwtSecretException.class)
            .hasMessageContaining("placeholder");
        assertThatCode(() -> ProdJwtSecretGuard.check(ascii(48))).doesNotThrowAnyException();
    }
}
