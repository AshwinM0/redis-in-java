package redis.resp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Wire-level constants for RESP.
 *
 * <p><b>Why ISO-8859-1?</b> RESP is a byte protocol: the {@code $N} header of a bulk
 * string counts <em>bytes</em>, and Redis values are binary-safe byte strings with no
 * declared encoding — the server never interprets them, it just stores and returns the
 * exact bytes it was given.
 *
 * <p>ISO-8859-1 maps bytes {@code 0x00..0xFF} one-to-one onto chars {@code U+0000..U+00FF},
 * so {@code new String(bytes, ISO_8859_1)} and {@code s.getBytes(ISO_8859_1)} round-trip
 * <em>any</em> byte sequence without loss. Two guarantees follow:
 * <ul>
 *   <li>{@code s.length()} always equals the byte length, so the {@code $N} header we
 *       emit is always correct;</li>
 *   <li>arbitrary binary payloads (UTF-8 text, protobuf, gzip, images) survive a
 *       SET/GET round-trip byte-for-byte.</li>
 * </ul>
 *
 * <p>Since Java 9's compact strings, a Latin-1 {@code String} is stored internally as one
 * byte per char, so this costs no extra memory.
 *
 * <p>Consequence: a String held by the server is a <em>byte string</em>, not display text.
 * Non-ASCII values look like mojibake in server-side logs; that is expected. The client
 * re-decodes the bytes in whatever encoding it sent them.
 */
public final class Protocol {

    private Protocol() {
    }

    /** The charset used to move between wire bytes and the server's internal Strings. */
    public static final Charset WIRE = StandardCharsets.ISO_8859_1;

    /** Redis rejects bulk strings larger than 512MB; so do we. */
    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;

    /** Redis rejects multibulk requests with more than 1M elements; so do we. */
    public static final int MAX_ARRAY_ELEMENTS = 1024 * 1024;

    /**
     * How deep RESP arrays may nest before the parser gives up.
     *
     * <p>A command is an array of bulk strings, so real traffic never nests past one
     * level. The parser recurses per level, and recursion driven by untrusted input needs
     * a bound: without one, a client can spend a few hundred kilobytes of {@code *1
}
     * to overflow the connection thread's stack.
     */
    public static final int MAX_NESTING_DEPTH = 32;

    /**
     * Longest header or simple-string line the parser will buffer.
     *
     * <p>Header lines hold a decimal count, so twenty bytes is generous. The cap exists
     * because the line is accumulated until a newline arrives, and a client that never
     * sends one would otherwise be handed unbounded server memory.
     */
    public static final int MAX_LINE_LENGTH = 64 * 1024;

    /**
     * Cap on the capacity the parser will reserve up front for an array.
     *
     * <p>The declared element count arrives long before the elements do, so it cannot be
     * trusted as an allocation size: {@code *1048576
} is ten bytes and used to buy a
     * million-slot array. The list still grows to hold whatever actually turns up, and
     * every element costs the client real bytes to send.
     */
    public static final int MAX_PREALLOCATED_ELEMENTS = 1024;

    public static byte[] toWire(String s) {
        return s.getBytes(WIRE);
    }

    public static String fromWire(byte[] bytes) {
        return new String(bytes, WIRE);
    }

    public static String fromWire(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, WIRE);
    }
}
