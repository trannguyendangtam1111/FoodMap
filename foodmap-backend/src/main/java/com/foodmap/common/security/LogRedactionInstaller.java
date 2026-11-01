package com.foodmap.common.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.nio.charset.Charset;
import java.util.Iterator;

/**
 * Makes every log line pass through {@link SecretRedactor} — message, MDC and
 * stack trace alike — whoever logs it (our code, RestTemplate, Hibernate).
 *
 * Runs right after Spring Boot has configured logging (and again once the
 * context is prepared, in case logging was re-initialised): wraps the encoder
 * of each logback output appender with {@link RedactingEncoder}, and
 * registers the configured Goong keys as secrets. Never fails the startup.
 * Registered in META-INF/spring.factories.
 */
public class LogRedactionInstaller implements GenericApplicationListener {

    /** Configuration values that are secrets wherever they appear. */
    static final String[] SECRET_PROPERTIES = {"app.goong.api-key", "app.goong.map-key"};

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        Class<?> type = eventType.getRawClass();
        return type != null && (ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(type)
            || ApplicationPreparedEvent.class.isAssignableFrom(type));
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        Environment env = event instanceof ApplicationEnvironmentPreparedEvent e ? e.getEnvironment()
            : event instanceof ApplicationPreparedEvent p ? p.getApplicationContext().getEnvironment() : null;
        if (env != null) {
            for (String name : SECRET_PROPERTIES) SecretRedactor.registerSecret(env.getProperty(name));
        }
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context && install(context) > 0) {
            LoggerFactory.getLogger(LogRedactionInstaller.class).info("Log secret redaction active");
        }
    }

    /** After LoggingApplicationListener: the appenders exist. */
    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }

    /**
     * Wraps the encoder of every output appender attached to any logger
     * (idempotent, never throws). @return appenders newly wrapped
     */
    public static int install(LoggerContext context) {
        int wrapped = 0;
        for (Logger logger : context.getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> appender = it.next();
                try {
                    if (appender instanceof OutputStreamAppender<ILoggingEvent> out && wrap(out)) wrapped++;
                } catch (RuntimeException e) {
                    context.getStatusManager().add(new ch.qos.logback.core.status.WarnStatus(
                        "log redaction not installed on appender " + appender.getName(), LogRedactionInstaller.class, e));
                }
            }
        }
        return wrapped;
    }

    private static boolean wrap(OutputStreamAppender<ILoggingEvent> appender) {
        Encoder<ILoggingEvent> encoder = appender.getEncoder();
        if (encoder == null || encoder instanceof RedactingEncoder) return false;
        RedactingEncoder redacting = new RedactingEncoder(encoder);
        redacting.setContext(appender.getContext());
        redacting.start();
        appender.setEncoder(redacting);
        return true;
    }

    /** The original encoder's bytes (formatted message + stack trace), decoded, redacted, re-encoded. */
    public static final class RedactingEncoder extends EncoderBase<ILoggingEvent> {
        private final Encoder<ILoggingEvent> delegate;
        private final Charset charset;

        public RedactingEncoder(Encoder<ILoggingEvent> delegate) {
            this.delegate = delegate;
            Charset c = delegate instanceof LayoutWrappingEncoder<ILoggingEvent> w ? w.getCharset() : null;
            this.charset = c != null ? c : Charset.defaultCharset();
        }

        public Encoder<ILoggingEvent> delegate() {
            return delegate;
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            byte[] raw = delegate.encode(event);
            if (raw == null || raw.length == 0) return raw;
            String text = new String(raw, charset);
            String safe = SecretRedactor.redact(text);
            return safe.equals(text) ? raw : safe.getBytes(charset);
        }

        @Override
        public byte[] headerBytes() {
            return delegate.headerBytes();
        }

        @Override
        public byte[] footerBytes() {
            return delegate.footerBytes();
        }
    }
}
