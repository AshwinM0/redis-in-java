package redis.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parser reads from a socket, so every buffer it grows and every frame it pushes is
 * sized by a stranger. Each case here cost the client almost nothing to send and cost the
 * server an unbounded amount before these limits existed.
 */
class RespParserLimitsTest {

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(Protocol.WIRE));
    }

    @Test
    @DisplayName("Deep nesting is rejected, not allowed to overflow the stack")
    void nestingIsBounded() {
        String deep = "*1\r\n".repeat(200_000) + "$1\r\nx\r\n";

        RespParseException e = assertThrows(RespParseException.class, () -> RespParser.readValue(bytes(deep)));
        assertTrue(e.getMessage().contains("nesting depth"), e.getMessage());
    }

    @Test
    @DisplayName("Nesting within the limit still parses")
    void nestingWithinTheLimitIsFine() throws Exception {
        String ok = "*1\r\n".repeat(Protocol.MAX_NESTING_DEPTH) + "$1\r\nx\r\n";

        Value v = RespParser.readValue(bytes(ok));
        for (int i = 0; i < Protocol.MAX_NESTING_DEPTH; i++) {
            assertEquals("array", v.typ);
            v = v.array.get(0);
        }
        assertEquals("x", v.str);
    }

    @Test
    @DisplayName("A declared element count does not become an allocation")
    void arrayHeaderDoesNotPreallocate() {
        // Ten bytes claiming a million elements. Previously this reserved the million
        // slots before a single element had arrived.
        long before = usedHeap();
        for (int i = 0; i < 200; i++) {
            assertThrows(Exception.class, () -> RespParser.readValue(bytes("*1048576\r\n")));
        }
        long grown = usedHeap() - before;

        assertTrue(grown < 64L * 1024 * 1024,
                "200 array headers grew the heap by " + (grown / (1024 * 1024)) + "MB");
    }

    @Test
    @DisplayName("The declared count is still honoured for arrays that really arrive")
    void largeArraysStillParseWhenTheElementsAreSent() throws Exception {
        int n = Protocol.MAX_PREALLOCATED_ELEMENTS * 4;
        StringBuilder sb = new StringBuilder("*" + n + "\r\n");
        for (int i = 0; i < n; i++) {
            sb.append("$1\r\nx\r\n");
        }

        Value v = RespParser.readValue(bytes(sb.toString()));
        assertEquals(n, v.array.size());
    }

    @Test
    @DisplayName("A header line with no terminator is cut off")
    void unterminatedLineIsBounded() {
        // A stream of digits that never yields a CRLF.
        InputStream endless = new InputStream() {
            private long sent = 0;

            @Override
            public int read() {
                return sent++ == 0 ? '*' : '9';
            }
        };

        RespParseException e = assertThrows(RespParseException.class, () -> RespParser.readValue(endless));
        assertTrue(e.getMessage().contains("line exceeds"), e.getMessage());
    }

    @Test
    @DisplayName("Ordinary traffic is unaffected")
    void normalCommandsStillParse() throws IOException, RespParseException {
        Value v = RespParser.readValue(bytes("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n"));

        assertEquals("array", v.typ);
        assertEquals(3, v.array.size());
        assertEquals("SET", v.array.get(0).str);
        assertEquals("value", v.array.get(2).str);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }
}
