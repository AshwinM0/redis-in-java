package redis.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.*;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PubSubCommandsTest {

    PubSubManager pubsub;
    Database db;
    RespWriter writer;
    ByteArrayOutputStream output;
    OutputStream out;

    @BeforeEach
    void beforeEach() {
        pubsub = new PubSubManager();
        db = new Database();
        writer = new RespWriter();
        output = new ByteArrayOutputStream();
        out = output;
    }

    private List<Value> makeArgs(String... args) {
        List<Value> values = new ArrayList<>();
        for (String arg : args) {
            Value v = new Value("bulk", arg);
            values.add(v);
        }
        return values;
    }

    @Nested
    @DisplayName("SubscribeCommand")
    class SubscribeCommandTest {

        @Test
        @DisplayName("should subscribe to a single channel")
        void testSubscribeSingleChannel() throws IOException {
            SubscribeCommand cmd = new SubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("SUBSCRIBE", "news"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("subscribe"));
            assertTrue(result.contains("news"));
            assertTrue(pubsub.isSubscribed(out));
        }

        @Test
        @DisplayName("should subscribe to multiple channels")
        void testSubscribeMultipleChannels() throws IOException {
            SubscribeCommand cmd = new SubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("SUBSCRIBE", "news", "sports", "weather"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("subscribe"));
            assertTrue(result.contains("news"));
            assertTrue(result.contains("sports"));
            assertTrue(result.contains("weather"));
            assertTrue(pubsub.isSubscribed(out));
        }

        @Test
        @DisplayName("should return error for missing channel argument")
        void testSubscribeMissingChannel() throws IOException {
            SubscribeCommand cmd = new SubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("SUBSCRIBE"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("ERR") || result.toLowerCase().contains("wrong number"));
            assertFalse(pubsub.isSubscribed(out));
        }
    }

    @Nested
    @DisplayName("UnsubscribeCommand")
    class UnsubscribeCommandTest {

        @Test
        @DisplayName("should unsubscribe from a specific channel")
        void testUnsubscribeSingleChannel() throws IOException {
            // First subscribe
            pubsub.subscribe(out, "news");
            pubsub.subscribe(out, "sports");
            assertTrue(pubsub.isSubscribed(out));

            // Then unsubscribe from one
            UnsubscribeCommand cmd = new UnsubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("UNSUBSCRIBE", "news"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("unsubscribe"));
            assertTrue(result.contains("news"));
            // Still subscribed to sports
            assertTrue(pubsub.isSubscribed(out));
        }

        @Test
        @DisplayName("should unsubscribe from multiple channels")
        void testUnsubscribeMultipleChannels() throws IOException {
            pubsub.subscribe(out, "news");
            pubsub.subscribe(out, "sports");
            pubsub.subscribe(out, "weather");

            UnsubscribeCommand cmd = new UnsubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("UNSUBSCRIBE", "news", "sports"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("unsubscribe"));
            // Still subscribed to weather
            assertTrue(pubsub.isSubscribed(out));
        }

        @Test
        @DisplayName("should unsubscribe from all channels when no channels specified")
        void testUnsubscribeAll() throws IOException {
            pubsub.subscribe(out, "news");
            pubsub.subscribe(out, "sports");
            assertTrue(pubsub.isSubscribed(out));

            UnsubscribeCommand cmd = new UnsubscribeCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("UNSUBSCRIBE"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("unsubscribe"));
            assertFalse(pubsub.isSubscribed(out));
        }
    }

    @Nested
    @DisplayName("PublishCommand")
    class PublishCommandTest {

        @Test
        @DisplayName("should publish message and return subscriber count")
        void testPublishToSubscribers() throws IOException {
            // Create a subscriber
            ByteArrayOutputStream subOutput = new ByteArrayOutputStream();
            OutputStream subscriber = subOutput;
            pubsub.subscribe(subscriber, "news");

            // Publish message
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "news", "Hello!"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            // Result should contain the integer 1 (number of receivers)
            assertTrue(result.contains(":1"));
        }

        @Test
        @DisplayName("should return 0 when publishing to empty channel")
        void testPublishNoSubscribers() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "empty", "Hello!"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains(":0"));
        }

        @Test
        @DisplayName("should return error for missing arguments")
        void testPublishMissingArgs() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("ERR") || result.toLowerCase().contains("wrong number"));
        }

        @Test
        @DisplayName("should return error when missing message argument")
        void testPublishMissingMessage() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "channel"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains("ERR") || result.toLowerCase().contains("wrong number"));
        }

        @Test
        @DisplayName("should deliver message to multiple subscribers")
        void testPublishMultipleSubscribers() throws IOException {
            // Create multiple subscribers
            OutputStream sub1 = new ByteArrayOutputStream();
            OutputStream sub2 = new ByteArrayOutputStream();
            OutputStream sub3 = new ByteArrayOutputStream();
            pubsub.subscribe(sub1, "news");
            pubsub.subscribe(sub2, "news");
            pubsub.subscribe(sub3, "news");

            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "news", "Breaking!"));
            out.flush();

            String result = output.toString(redis.resp.Protocol.WIRE);
            assertTrue(result.contains(":3"));
        }
    }

    private String run(Command cmd, String... args) throws IOException {
        output.reset();
        cmd.execute(db, writer, out, makeArgs(args));
        out.flush();
        return output.toString(redis.resp.Protocol.WIRE);
    }

    @Nested
    @DisplayName("PSubscribeCommand")
    class PSubscribeCommandTest {

        @Test
        @DisplayName("confirms each pattern with the running count of channels and patterns")
        void confirmsEachPattern() throws IOException {
            pubsub.subscribe(out, "news");
            String result = run(new PSubscribeCommand(pubsub), "PSUBSCRIBE", "a.*", "b.*");

            assertEquals("*3\r\n$10\r\npsubscribe\r\n$3\r\na.*\r\n:2\r\n"
                    + "*3\r\n$10\r\npsubscribe\r\n$3\r\nb.*\r\n:3\r\n", result);
        }

        @Test
        @DisplayName("needs at least one pattern")
        void needsAPattern() throws IOException {
            String result = run(new PSubscribeCommand(pubsub), "PSUBSCRIBE");

            assertEquals("-ERR wrong number of arguments for 'psubscribe' command\r\n", result);
            assertFalse(pubsub.isSubscribed(out));
        }
    }

    @Nested
    @DisplayName("PUnsubscribeCommand")
    class PUnsubscribeCommandTest {

        @Test
        @DisplayName("ends a named pattern, counting the channels that remain")
        void endsANamedPattern() throws IOException {
            pubsub.subscribe(out, "news");
            pubsub.psubscribe(out, "a.*");

            String result = run(new PUnsubscribeCommand(pubsub), "PUNSUBSCRIBE", "a.*");

            assertEquals("*3\r\n$12\r\npunsubscribe\r\n$3\r\na.*\r\n:1\r\n", result);
        }

        @Test
        @DisplayName("with no arguments, ends every pattern with one reply each, and leaves channels alone")
        void endsEveryPattern() throws IOException {
            pubsub.subscribe(out, "news");
            pubsub.psubscribe(out, "a.*");
            pubsub.psubscribe(out, "b.*");

            String result = run(new PUnsubscribeCommand(pubsub), "PUNSUBSCRIBE");

            assertEquals(2, result.split("\\$12\r\npunsubscribe", -1).length - 1, result);
            assertTrue(result.endsWith(":1\r\n"), "the channel still counts: " + result);
            assertEquals(List.of(), pubsub.patternsOf(out));
            assertTrue(pubsub.isSubscribed(out));
        }

        @Test
        @DisplayName("with nothing to end, replies once with a null pattern")
        void nothingToEnd() throws IOException {
            String result = run(new PUnsubscribeCommand(pubsub), "PUNSUBSCRIBE");

            assertEquals("*3\r\n$12\r\npunsubscribe\r\n$-1\r\n:0\r\n", result);
        }
    }

    @Nested
    @DisplayName("UnsubscribeCommand with patterns")
    class UnsubscribeWithPatternsTest {

        @Test
        @DisplayName("with no arguments, ends every channel with one reply each, and leaves patterns alone")
        void endsEveryChannelOnly() throws IOException {
            pubsub.subscribe(out, "news");
            pubsub.subscribe(out, "sport");
            pubsub.psubscribe(out, "a.*");

            String result = run(new UnsubscribeCommand(pubsub), "UNSUBSCRIBE");

            assertEquals(2, result.split("\\$11\r\nunsubscribe", -1).length - 1, result);
            assertTrue(result.contains("$4\r\nnews\r\n") && result.contains("$5\r\nsport\r\n"), result);
            assertTrue(result.endsWith(":1\r\n"), "the pattern still counts: " + result);
            assertEquals(List.of("a.*"), pubsub.patternsOf(out));
        }

        @Test
        @DisplayName("with nothing to end, replies once with a null channel and the pattern count")
        void nothingToEnd() throws IOException {
            pubsub.psubscribe(out, "a.*");

            String result = run(new UnsubscribeCommand(pubsub), "UNSUBSCRIBE");

            assertEquals("*3\r\n$11\r\nunsubscribe\r\n$-1\r\n:1\r\n", result);
        }
    }

    @Nested
    @DisplayName("PubSubCommand")
    class PubSubCommandTest {

        @Test
        @DisplayName("NUMSUB pairs each channel with its subscriber count")
        void numSub() throws IOException {
            pubsub.subscribe(new ByteArrayOutputStream(), "news");
            pubsub.subscribe(new ByteArrayOutputStream(), "news");
            pubsub.psubscribe(new ByteArrayOutputStream(), "n*"); // pattern subscribers do not count

            String result = run(new PubSubCommand(pubsub), "PUBSUB", "NUMSUB", "news", "empty");

            assertEquals("*4\r\n$4\r\nnews\r\n:2\r\n$5\r\nempty\r\n:0\r\n", result);
        }

        @Test
        @DisplayName("NUMSUB with no channels is an empty array")
        void numSubNoChannels() throws IOException {
            assertEquals("*0\r\n", run(new PubSubCommand(pubsub), "PUBSUB", "numsub"));
        }

        @Test
        @DisplayName("NUMPAT counts distinct patterns")
        void numPat() throws IOException {
            pubsub.psubscribe(new ByteArrayOutputStream(), "a.*");
            pubsub.psubscribe(new ByteArrayOutputStream(), "a.*");
            pubsub.psubscribe(new ByteArrayOutputStream(), "b.*");

            assertEquals(":2\r\n", run(new PubSubCommand(pubsub), "PUBSUB", "NUMPAT"));
        }

        @Test
        @DisplayName("CHANNELS lists active channels, filtered by an optional pattern")
        void channels() throws IOException {
            pubsub.subscribe(new ByteArrayOutputStream(), "news.tech");
            pubsub.subscribe(new ByteArrayOutputStream(), "sport");

            assertEquals("*1\r\n$9\r\nnews.tech\r\n",
                    run(new PubSubCommand(pubsub), "PUBSUB", "CHANNELS", "news.*"));
            assertTrue(run(new PubSubCommand(pubsub), "PUBSUB", "CHANNELS").startsWith("*2\r\n"));
        }

        @Test
        @DisplayName("rejects unknown subcommands and wrong arities")
        void errors() throws IOException {
            PubSubCommand cmd = new PubSubCommand(pubsub);
            assertEquals("-ERR wrong number of arguments for 'pubsub' command\r\n", run(cmd, "PUBSUB"));
            assertEquals("-ERR unknown subcommand 'nope'. Try PUBSUB HELP.\r\n", run(cmd, "PUBSUB", "nope"));
            assertEquals("-ERR wrong number of arguments for 'pubsub|numpat' command\r\n",
                    run(cmd, "PUBSUB", "NUMPAT", "extra"));
            assertEquals("-ERR wrong number of arguments for 'pubsub|channels' command\r\n",
                    run(cmd, "PUBSUB", "CHANNELS", "a", "b"));
        }
    }
}
