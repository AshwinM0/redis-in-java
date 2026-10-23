package redis.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import redis.resp.Protocol;

/**
 * The server's password, as AUTH checks it.
 *
 * <p>Only a SHA-256 digest is kept. Each attempt is hashed the same way, and the two digests
 * are compared with {@link MessageDigest#isEqual}, which takes the same time however many
 * bytes match. Because digests are always 32 bytes, the comparison's timing also gives away
 * nothing about how long the real password is. Redis compares SHA-256 digests the same way.
 */
public final class Password {

    /** No password configured: every connection starts out authenticated. */
    public static final Password NONE = new Password(null);

    private final byte[] digest; // null when there is no password

    private Password(byte[] digest) {
        this.digest = digest;
    }

    /** @param configured the password from config; null or empty for none, as in Redis */
    public static Password of(String configured) {
        if (configured == null || configured.isEmpty()) {
            return NONE;
        }
        // Clients send the password as UTF-8 bytes, so that is what gets hashed.
        return new Password(sha256(configured.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean isSet() {
        return digest != null;
    }

    /**
     * Whether an AUTH attempt carries the password.
     *
     * @param attempt as the server holds request arguments: one char per wire byte
     */
    public boolean matches(String attempt) {
        return digest != null && MessageDigest.isEqual(digest, sha256(Protocol.toWire(attempt)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("every Java platform is required to support SHA-256", e);
        }
    }
}
