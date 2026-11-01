package com.foodmap.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    static final String KEY = "GoongKey-0123456789abcdef";

    @AfterEach
    void forget() {
        SecretRedactor.clearRegisteredSecrets();
    }

    static String printed(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void goongUrlsLoseTheirKey() {
        assertThat(SecretRedactor.redact("GET https://rsapi.goong.io/Place/Detail?place_id=abc&api_key=" + KEY + " failed"))
            .isEqualTo("GET https://rsapi.goong.io/Place/Detail?place_id=abc&api_key=*** failed");
        assertThat(SecretRedactor.redact("https://x/place/autocomplete?api_key=" + KEY + "&input=Ph%E1%BB%9F"))
            .isEqualTo("https://x/place/autocomplete?api_key=***&input=Ph%E1%BB%9F");
        assertThat(SecretRedactor.redact("tiles?key=" + KEY)).isEqualTo("tiles?key=***");
        assertThat(SecretRedactor.redact("a?access_token=" + KEY + "&b=1")).isEqualTo("a?access_token=***&b=1");
    }

    @Test
    void assignmentsAndHeadersAreMasked() {
        assertThat(SecretRedactor.redact("{\"api_key\":\"" + KEY + "\",\"x\":1}")).isEqualTo("{\"api_key\":\"***\",\"x\":1}");
        assertThat(SecretRedactor.redact("GoongProperties(apiKey=" + KEY + ", baseUrl=x)"))
            .isEqualTo("GoongProperties(apiKey=***, baseUrl=x)");
        assertThat(SecretRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
            .doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(SecretRedactor.redact("token Bearer abcdefghijkl")).isEqualTo("token Bearer ***");
    }

    @Test
    void aRegisteredSecretIsMaskedWherever_itAppears() {
        SecretRedactor.registerSecret(KEY);
        assertThat(SecretRedactor.redact("the body echoed " + KEY + " back")).isEqualTo("the body echoed *** back");
        SecretRedactor.registerSecret("abc");   // too short to register: would mask ordinary text
        assertThat(SecretRedactor.redact("abc")).isEqualTo("abc");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String text = "food:pho@quan-5 place_id=abc input=Phở cacheKey=1 the bearer of news (Bánh mì 51)";
        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
        assertThat(SecretRedactor.redact(null)).isNull();
        assertThat(SecretRedactor.redact("")).isEmpty();
    }

    @Test
    void sanitizedExceptionsCarryNoSecretInMessageCauseOrStackTrace() {
        IOException inner = new IOException("Connection reset: https://x/place/detail?place_id=p&api_key=" + KEY);
        RuntimeException outer = new IllegalStateException("I/O error on GET request for \"https://x?api_key=" + KEY + "\"", inner);
        outer.addSuppressed(new RuntimeException("suppressed ?token=" + KEY));
        assertThat(printed(outer)).contains(KEY);   // the original leaks

        RuntimeException safe = SecretRedactor.sanitize(outer);

        assertThat(printed(safe)).doesNotContain(KEY)
            .contains("java.lang.IllegalStateException", "java.io.IOException", "api_key=***");
        assertThat(safe.getMessage()).startsWith("java.lang.IllegalStateException: ");
        assertThat(safe.getCause()).isNotSameAs(inner);
        assertThat(safe.getCause().getMessage()).doesNotContain(KEY);
        assertThat(safe.getStackTrace()).isEqualTo(outer.getStackTrace());
        assertThat(safe.getCause().getStackTrace()).isEqualTo(inner.getStackTrace());
        assertThat(SecretRedactor.sanitize(safe)).isSameAs(safe);
        assertThat(SecretRedactor.sanitize(null)).isNull();
    }
}
