package com.foodmap.common.security;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from text that may reach logs, exceptions, reports or stack
 * traces: Goong URLs carry {@code api_key} in the query string, and an HTTP
 * client error can echo the request URL.
 *
 *   query / form parameters  api_key=…, key=…, token=…, access_token=…, secret=…, password=…, signature=…
 *   assignments              "api_key": "…", apiKey=…, client_secret: …
 *   headers                  Authorization: Bearer …, Bearer …
 *   known values             every secret registered at startup (the configured Goong keys), wherever it appears
 *
 * Each secret becomes {@value #MASK}. Safe for any input (null stays null).
 */
public final class SecretRedactor {

    public static final String MASK = "***";

    /** Shorter registered values are ignored: too likely to match ordinary text. */
    static final int MIN_SECRET_LENGTH = 6;

    private static final Set<String> SECRETS = new CopyOnWriteArraySet<>();

    private static final String NAMES = "api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|token"
        + "|client[_-]?secret|secret|password|passwd|signature|sig|key";

    // Values stop at a backslash too: inside JSON an escaped quote (\") must stay intact.
    private static final List<Pattern> PATTERNS = List.of(
        // ?api_key=…  &token=…   (URL query or form body; value up to the next separator)
        Pattern.compile("(?i)([?&;](?:" + NAMES + ")=)[^&#\\s\"'<>;\\\\]*"),
        // "api_key":"…"  /  api_key = …  /  apiKey: …   (JSON, properties, toString output)
        Pattern.compile("(?i)(\"?\\b(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\"?\\s*[:=]\\s*\"?)[^\"&\\s,;}\\]\\\\]+"),
        // Authorization: Bearer xyz / Authorization=Basic xyz
        Pattern.compile("(?i)(\\bauthorization\\s*[:=]\\s*(?:bearer|basic|token)?\\s*)[^\\s,;\"'\\\\]+"),
        // Bearer xyz (anywhere)
        Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}")
    );

    private SecretRedactor() {
    }

    /** Remembers a secret value (e.g. a configured API key) so it is masked wherever it appears. */
    public static void registerSecret(String value) {
        if (value != null && value.trim().length() >= MIN_SECRET_LENGTH) SECRETS.add(value.trim());
    }

    /** Test support: forget registered values. */
    static void clearRegisteredSecrets() {
        SECRETS.clear();
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String secret : SECRETS) {
            if (out.contains(secret)) out = out.replace(secret, MASK);
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(out);
            if (m.find()) out = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + MASK));
        }
        return out;
    }

    /**
     * A copy of the throwable (and its whole cause chain) whose messages are
     * redacted: same stack traces, the original class kept in the message.
     * The original objects — whose messages may hold a URL with a key — are
     * not referenced by the copy.
     */
    public static RuntimeException sanitize(Throwable t) {
        if (t == null) return null;
        if (t instanceof RedactedException r) return r;
        String original = t.getMessage();
        String message = t.getClass().getName() + (original == null ? "" : ": " + redact(original));
        RedactedException copy = new RedactedException(message, sanitize(t.getCause()));
        copy.setStackTrace(t.getStackTrace());
        for (Throwable s : t.getSuppressed()) copy.addSuppressed(sanitize(s));
        return copy;
    }

    /** A throwable rebuilt by {@link #sanitize}: its message names the original class. */
    public static final class RedactedException extends RuntimeException {
        RedactedException(String message, Throwable cause) {
            super(message, cause);
        }

        /** The message already starts with the original class name. */
        @Override
        public String toString() {
            return getMessage();
        }
    }
}
