package redis.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import redis.core.CommandProcessor;
import redis.core.Database;

/**
 * Simple strings and errors end at the first CRLF, so anything client-controlled that
 * reaches one has to be unable to carry a CRLF. Several such payloads exist: an unknown
 * command name, a rejected command name, a PING argument. Left unchecked, a client can
 * append replies of its own choosing to its stream and leave it permanently one reply
 * ahead — and on a pooled connection those forged replies go to whoever borrows it next.
 */
class ReplyInjectionTest {

    private final RespWriter writer = new RespWriter();

    private String written(Write action) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        action.run(out);
        return out.toString(Protocol.WIRE);
    }

    private interface Write {
        void run(ByteArrayOutputStream out) throws IOException;
    }

    /** How many replies a client reading the stream would count. */
    private static int replies(String wire) {
        assertTrue(wire.endsWith(Protocol.CRLF_TEXT), "reply was not terminated: " + wire);
        return wire.split(Protocol.CRLF_TEXT, -1).length - 1;
    }

    @Nested
    @DisplayName("A CRLF in a line reply cannot end the frame")
    class LineReplies {

        @Test
        void simpleStringStaysOneReply() throws IOException {
            String wire = written(out -> writer.writeSimple(out, "pong\r\n+INJECTED"));

            assertEquals(1, replies(wire), "the payload started a second reply: " + wire);
            assertEquals("+pong  +INJECTED" + Protocol.CRLF_TEXT, wire);
        }

        @Test
        void errorStaysOneReply() throws IOException {
            String wire = written(out -> writer.writeError(out, "unknown command 'x\r\n-ERR forged'"));

            assertEquals(1, replies(wire), "the payload started a second reply: " + wire);
            assertFalse(wire.substring(0, wire.length() - 2).contains(Protocol.CRLF_TEXT));
        }

        @Test
        void aBareCarriageReturnOrNewlineIsAlsoNeutralised() throws IOException {
            assertEquals("+a b" + Protocol.CRLF_TEXT, written(out -> writer.writeSimple(out, "a\rb")));
            assertEquals("+a b" + Protocol.CRLF_TEXT, written(out -> writer.writeSimple(out, "a\nb")));
        }

        @Test
        void ordinaryRepliesAreUntouched() throws IOException {
            assertEquals("+OK" + Protocol.CRLF_TEXT, written(out -> writer.writeSimple(out, "OK")));
            assertEquals("-ERR nope" + Protocol.CRLF_TEXT, written(out -> writer.writeError(out, "nope")));
        }
    }

    @Nested
    @DisplayName("Bulk strings keep carrying whatever bytes they were given")
    class BulkReplies {

        @Test
        void crlfInsideABulkStringIsData() throws IOException {
            String payload = "line1\r\nline2";
            String wire = written(out -> writer.writeBulk(out, payload));

            // Length-prefixed, so the CRLF is inside the declared 12 bytes and the value
            // survives a round trip. Sanitising here would break binary safety.
            assertEquals("$12" + Protocol.CRLF_TEXT + payload + Protocol.CRLF_TEXT, wire);
        }

        @Test
        void everyByteValueSurvives() throws IOException {
            StringBuilder all = new StringBuilder();
            for (int b = 0; b < 256; b++) {
                all.append((char) b);
            }
            String payload = all.toString();

            String wire = written(out -> writer.writeBulk(out, payload));

            assertEquals("$256" + Protocol.CRLF_TEXT + payload + Protocol.CRLF_TEXT, wire);
        }
    }

    @Nested
    @DisplayName("The command paths that echo client input stay one reply")
    class Sinks {

        private final Database db = new Database();
        private final CommandProcessor processor = new CommandProcessor();

        private String run(String... parts) throws IOException {
            List<Value> args = new ArrayList<>();
            for (String p : parts) {
                args.add(new Value("bulk", p));
            }
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            processor.executeCommand(parts[0], db, writer, reply, args, true);
            db.shutdown();
            return reply.toString(Protocol.WIRE);
        }

        @Test
        void unknownCommandNameCannotForgeAReply() throws IOException {
            String wire = run("bogus\r\n-ERR forged error");

            assertEquals(1, replies(wire), "the command name started a second reply: " + wire);
        }

        @Test
        void pingArgumentCannotForgeAReply() throws IOException {
            String wire = run("PING", "pong\r\n+INJECTED");

            // PING's argument form is a bulk string, so the argument comes back intact
            // rather than being flattened -- and still as exactly one reply.
            assertEquals("$15" + Protocol.CRLF_TEXT + "pong\r\n+INJECTED" + Protocol.CRLF_TEXT, wire);
        }
    }

    // The third sink, ClientHandler's "not allowed while subscribed" message, echoes the
    // command name through this same writeError and is covered by the cases above.
}
