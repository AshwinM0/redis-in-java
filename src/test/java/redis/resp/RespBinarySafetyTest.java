package redis.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RESP is a byte protocol: {@code $N} counts bytes. These tests pin that invariant down
 * across the parser and the writer, for text and for arbitrary binary.
 */
class RespBinarySafetyTest {

    /** Builds a RESP command the way a real client does: lengths measured in bytes. */
    private static byte[] wireCommand(byte[]... args) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : args) {
            b.write(("$" + arg.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            b.write(arg);
            b.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return b.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Parse one command, then serialise it back out. Output must equal input, byte for byte. */
    private static byte[] roundTrip(byte[] wire) throws IOException, RespParseException {
        Value parsed = RespParser.readValue(new ByteArrayInputStream(wire));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RespWriter().writeRequest(out, parsed);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("values survive a round trip byte-for-byte")
    class RoundTrip {

        @Test
        @DisplayName("multi-byte UTF-8 (accented text)")
        void utf8Accented() throws Exception {
            byte[] wire = wireCommand(utf8("SET"), utf8("name"), utf8("Jos\u00e9"));
            assertArrayEquals(wire, roundTrip(wire));
        }

        @Test
        @DisplayName("4-byte UTF-8 (emoji)")
        void utf8Emoji() throws Exception {
            byte[] wire = wireCommand(utf8("SET"), utf8("mood"), utf8("caf\u00e9 \u2615 \ud83d\ude80"));
            assertArrayEquals(wire, roundTrip(wire));
        }

        @Test
        @DisplayName("every byte value 0x00-0xFF, including embedded CR, LF and NUL")
        void arbitraryBinary() throws Exception {
            byte[] blob = new byte[256];
            for (int i = 0; i < 256; i++) {
                blob[i] = (byte) i;
            }
            byte[] wire = wireCommand(utf8("SET"), utf8("blob"), blob);

            Value parsed = RespParser.readValue(new ByteArrayInputStream(wire));
            assertEquals(3, parsed.array.size());
            assertEquals(256, parsed.array.get(2).str.length(),
                    "a 256-byte payload must be 256 chars internally");
            assertArrayEquals(wire, roundTrip(wire));
        }
    }

    @Nested
    @DisplayName("length headers count bytes, not chars")
    class LengthHeaders {

        @Test
        @DisplayName("writeBulk declares the encoded byte length")
        void bulkHeaderIsByteLength() throws Exception {
            // The parser hands us a byte-string; "José" is 5 bytes -> 5 internal chars.
            Value parsed = RespParser.readValue(
                    new ByteArrayInputStream(wireCommand(utf8("Jos\u00e9"))));
            String value = parsed.array.get(0).str;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new RespWriter().writeBulk(out, value);

            byte[] written = out.toByteArray();
            assertEquals("$5\r\n", new String(written, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(5 + 4 + 2, written.length, "header + 5 payload bytes + CRLF");
        }

        @Test
        @DisplayName("declared length always matches the bytes that follow")
        void declaredLengthMatchesPayload() throws Exception {
            Value parsed = RespParser.readValue(
                    new ByteArrayInputStream(wireCommand(utf8("\u2615\ud83d\ude80"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new RespWriter().writeBulk(out, parsed.array.get(0).str);
            byte[] written = out.toByteArray();

            int crlf = indexOfCrlf(written, 0);
            int declared = Integer.parseInt(new String(written, 1, crlf - 1, StandardCharsets.US_ASCII));
            int actual = written.length - (crlf + 2) - 2; // minus header and trailing CRLF
            assertEquals(declared, actual);
        }

        private int indexOfCrlf(byte[] b, int from) {
            for (int i = from; i < b.length - 1; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }

    @Nested
    @DisplayName("stream handling")
    class Streaming {

        @Test
        @DisplayName("a bulk string split across many reads is reassembled")
        void reassemblesShortReads() throws Exception {
            byte[] blob = new byte[70_000];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) (i % 251);
            }
            byte[] wire = wireCommand(utf8("SET"), utf8("big"), blob);

            // Deliver at most 1400 bytes per read, the way a real TCP stream would.
            InputStream trickle = new InputStream() {
                private final ByteArrayInputStream src = new ByteArrayInputStream(wire);

                @Override
                public int read() {
                    return src.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return src.read(b, off, Math.min(len, 1400));
                }
            };

            Value parsed = RespParser.readValue(trickle);
            assertEquals(blob.length, parsed.array.get(2).str.length());
            assertArrayEquals(wire, roundTrip(wire));
        }

        @Test
        @DisplayName("an absurd bulk length is rejected instead of allocated")
        void rejectsOversizedBulkLength() {
            byte[] wire = "$2000000000\r\n".getBytes(StandardCharsets.US_ASCII);
            assertThrows(RespParseException.class,
                    () -> RespParser.readValue(new ByteArrayInputStream(wire)));
        }

        @Test
        @DisplayName("a negative multibulk length is rejected")
        void rejectsNegativeMultibulk() {
            byte[] wire = "*-9\r\n".getBytes(StandardCharsets.US_ASCII);
            assertThrows(RespParseException.class,
                    () -> RespParser.readValue(new ByteArrayInputStream(wire)));
        }
    }
}
