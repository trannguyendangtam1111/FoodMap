package com.foodmap.common.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.ResolvableType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Every log line — message and stack trace — passes through the redactor once installed. */
class LogRedactionInstallerTest {

    static final String KEY = "GoongKey-0123456789abcdef";

    final LoggerContext context = new LoggerContext();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    OutputStreamAppender<ILoggingEvent> appender;
    Logger logger;

    @BeforeEach
    void setUp() {
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());   // a bare context has none
        context.start();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%-5level %logger - %msg%n");   // no exception converter: logback appends one
        encoder.start();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();
        logger = context.getLogger("goong.test");
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        SecretRedactor.clearRegisteredSecrets();
        context.stop();
    }

    String logged() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutTheInstallerTheKeyLeaks() {
        logger.warn("GET https://x/place/detail?api_key={} failed", KEY);
        assertThat(logged()).contains(KEY);
    }

    @Test
    void messagesAndStackTracesAreRedacted() {
        assertThat(LogRedactionInstaller.install(context)).isEqualTo(1);
        assertThat(LogRedactionInstaller.install(context)).isZero();   // idempotent: no double wrapping

        logger.warn("GET https://x/place/detail?place_id=p&api_key={} failed", KEY,
            new IllegalStateException("I/O error on GET request for \"https://x?api_key=" + KEY + "\"",
                new java.io.IOException("reset ?api_key=" + KEY)));

        assertThat(logged()).doesNotContain(KEY)
            .contains("api_key=***", "IllegalStateException", "IOException", "at com.foodmap");
        assertThat(appender.getEncoder()).isInstanceOf(LogRedactionInstaller.RedactingEncoder.class);
        assertThat(((LogRedactionInstaller.RedactingEncoder) appender.getEncoder()).delegate())
            .isInstanceOf(PatternLayoutEncoder.class);   // wrapped once, not twice
    }

    @Test
    void registeredSecretsAreMaskedInAnyForm() {
        LogRedactionInstaller.install(context);
        SecretRedactor.registerSecret(KEY);
        logger.info("config loaded: {}", KEY);
        assertThat(logged()).doesNotContain(KEY).contains("config loaded: ***");
    }

    @Test
    void listensRightAfterSpringBootConfiguresLogging() {
        LogRedactionInstaller installer = new LogRedactionInstaller();
        assertThat(installer.supportsEventType(ResolvableType.forClass(ApplicationEnvironmentPreparedEvent.class))).isTrue();
        assertThat(installer.supportsEventType(ResolvableType.forClass(ApplicationReadyEvent.class))).isFalse();
        assertThat(installer.getOrder())
            .isGreaterThan(org.springframework.boot.context.logging.LoggingApplicationListener.DEFAULT_ORDER);
    }
}
