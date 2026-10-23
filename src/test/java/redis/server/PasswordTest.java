package redis.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.resp.Protocol;

class PasswordTest {

    /** An AUTH argument as the server holds it after parsing: one char per wire byte. */
    private static String asSentByAClient(String password) {
        return Protocol.fromWire(password.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("No password, or an empty one, means none is required, as in Redis")
    void unsetOrEmptyIsNone() {
        assertSame(Password.NONE, Password.of(null));
        assertSame(Password.NONE, Password.of(""));
        assertFalse(Password.NONE.isSet());
        assertFalse(Password.NONE.matches(""), "with no password there is nothing to match");
    }

    @Test
    void matchesOnlyTheExactPassword() {
        Password p = Password.of("s3cret");

        assertTrue(p.isSet());
        assertTrue(p.matches("s3cret"));
        assertFalse(p.matches("s3cre"), "a prefix");
        assertFalse(p.matches("s3cret!"), "a longer guess");
        assertFalse(p.matches("S3CRET"), "different case");
        assertFalse(p.matches(""));
    }

    /**
     * Clients send UTF-8, and the server keeps each wire byte as one char. Comparing the
     * configured Java string to that directly would reject every non-ASCII password.
     */
    @Test
    @DisplayName("A non-ASCII password matches the UTF-8 bytes a client sends")
    void nonAsciiPasswordComparesAsBytes() {
        String configured = "pässwörd ☕";
        Password p = Password.of(configured);

        assertTrue(p.matches(asSentByAClient(configured)));
        assertFalse(p.matches(configured), "the Java string is not what arrives on the wire");
    }
}
