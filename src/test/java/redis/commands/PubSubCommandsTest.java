package redis.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
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
    StringWriter output;
    BufferedWriter out;

    @BeforeEach
    void beforeEach() {
        pubsub = new PubSubManager();
        db = new Database();
        writer = new RespWriter();
        output = new StringWriter();
        out = new BufferedWriter(output);
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

            String result = output.toString();
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

            String result = output.toString();
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

            String result = output.toString();
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

            String result = output.toString();
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

            String result = output.toString();
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

            String result = output.toString();
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
            StringWriter subOutput = new StringWriter();
            BufferedWriter subscriber = new BufferedWriter(subOutput);
            pubsub.subscribe(subscriber, "news");

            // Publish message
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "news", "Hello!"));
            out.flush();

            String result = output.toString();
            // Result should contain the integer 1 (number of receivers)
            assertTrue(result.contains(":1"));
        }

        @Test
        @DisplayName("should return 0 when publishing to empty channel")
        void testPublishNoSubscribers() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "empty", "Hello!"));
            out.flush();

            String result = output.toString();
            assertTrue(result.contains(":0"));
        }

        @Test
        @DisplayName("should return error for missing arguments")
        void testPublishMissingArgs() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH"));
            out.flush();

            String result = output.toString();
            assertTrue(result.contains("ERR") || result.toLowerCase().contains("wrong number"));
        }

        @Test
        @DisplayName("should return error when missing message argument")
        void testPublishMissingMessage() throws IOException {
            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "channel"));
            out.flush();

            String result = output.toString();
            assertTrue(result.contains("ERR") || result.toLowerCase().contains("wrong number"));
        }

        @Test
        @DisplayName("should deliver message to multiple subscribers")
        void testPublishMultipleSubscribers() throws IOException {
            // Create multiple subscribers
            BufferedWriter sub1 = new BufferedWriter(new StringWriter());
            BufferedWriter sub2 = new BufferedWriter(new StringWriter());
            BufferedWriter sub3 = new BufferedWriter(new StringWriter());
            pubsub.subscribe(sub1, "news");
            pubsub.subscribe(sub2, "news");
            pubsub.subscribe(sub3, "news");

            PublishCommand cmd = new PublishCommand(pubsub);
            cmd.execute(db, writer, out, makeArgs("PUBLISH", "news", "Breaking!"));
            out.flush();

            String result = output.toString();
            assertTrue(result.contains(":3"));
        }
    }
}
