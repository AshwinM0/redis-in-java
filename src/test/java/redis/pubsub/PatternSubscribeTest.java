package redis.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.resp.Protocol;

/**
 * PSUBSCRIBE: subscriptions to every channel matching a glob-style pattern, delivered as
 * ["pmessage", pattern, channel, data].
 */
class PatternSubscribeTest {

    PubSubManager pubsub;
    ByteArrayOutputStream a;
    ByteArrayOutputStream b;

    @BeforeEach
    void beforeEach() {
        // Writers run on the publishing thread, so delivered messages can be read at once.
        pubsub = new PubSubManager(PubSubManager.DEFAULT_OUTPUT_LIMIT_BYTES, Runnable::run);
        a = new ByteArrayOutputStream();
        b = new ByteArrayOutputStream();
    }

    @Test
    @DisplayName("A matching publish arrives as a pmessage naming the pattern and the channel")
    void matchingPublishIsDelivered() {
        pubsub.psubscribe(a, "news.*");

        assertEquals(1, pubsub.publish("news.tech", "hi"));
        assertEquals("*4\r\n$8\r\npmessage\r\n$6\r\nnews.*\r\n$9\r\nnews.tech\r\n$2\r\nhi\r\n",
                a.toString(Protocol.WIRE));
    }

    @Test
    @DisplayName("A publish that matches no pattern reaches nobody")
    void nonMatchingPublishIsNotDelivered() {
        pubsub.psubscribe(a, "news.*");

        assertEquals(0, pubsub.publish("sport.football", "goal"));
        assertEquals(0, a.size());
    }

    @Test
    @DisplayName("Subscribed by channel and by pattern, a client gets the message once for each")
    void channelAndPatternEachDeliver() {
        pubsub.subscribe(a, "news.tech");
        pubsub.psubscribe(a, "news.*");
        pubsub.psubscribe(a, "*.tech");

        assertEquals(3, pubsub.publish("news.tech", "hi"));
        String wire = a.toString(Protocol.WIRE);
        assertTrue(wire.startsWith("*3\r\n$7\r\nmessage\r\n"), "the channel's own message comes first: " + wire);
        assertTrue(wire.contains("$6\r\nnews.*\r\n"));
        assertTrue(wire.contains("$6\r\n*.tech\r\n"));
    }

    @Test
    @DisplayName("Reply counts cover channels and patterns together, as Redis's do")
    void countsIncludeBothKinds() {
        assertEquals(1, pubsub.subscribe(a, "news"));
        assertEquals(2, pubsub.psubscribe(a, "news.*"));
        assertEquals(2, pubsub.psubscribe(a, "news.*")); // already subscribed
        assertEquals(1, pubsub.unsubscribe(a, "news"));
        assertEquals(1, pubsub.unsubscribe(a, "never-subscribed"));
        assertEquals(0, pubsub.punsubscribe(a, "news.*"));
    }

    @Test
    @DisplayName("A client subscribed only to patterns counts as subscribed")
    void patternsAloneMakeAClientSubscribed() {
        pubsub.psubscribe(a, "news.*");
        assertTrue(pubsub.isSubscribed(a));

        pubsub.punsubscribe(a, "news.*");
        assertFalse(pubsub.isSubscribed(a));
    }

    @Test
    @DisplayName("After PUNSUBSCRIBE the pattern delivers nothing more")
    void punsubscribeStopsDelivery() {
        pubsub.psubscribe(a, "news.*");
        pubsub.punsubscribe(a, "news.*");

        assertEquals(0, pubsub.publish("news.tech", "hi"));
        assertEquals(0, pubsub.numPat());
    }

    @Test
    @DisplayName("unsubscribeAll ends patterns as well as channels")
    void unsubscribeAllEndsPatterns() {
        pubsub.subscribe(a, "news");
        pubsub.psubscribe(a, "news.*");

        pubsub.unsubscribeAll(a);

        assertFalse(pubsub.isSubscribed(a));
        assertEquals(0, pubsub.publish("news.tech", "hi"));
        assertEquals(List.of(), pubsub.patternsOf(a));
    }

    /**
     * Pattern messages and the connection's own replies have to share one lock. The lock used
     * to be thrown away as soon as a connection's channel count reached zero, which with
     * patterns still subscribed would have given the two writers different locks over the
     * same socket.
     */
    @Test
    @DisplayName("A connection keeps one lock while it is open, whatever its subscriptions")
    void lockSurvivesUnsubscribing() {
        Object lock = pubsub.lockFor(a);
        pubsub.subscribe(a, "news");
        pubsub.psubscribe(a, "news.*");

        pubsub.unsubscribe(a, "news"); // no channels left, a pattern still subscribed
        assertSame(lock, pubsub.lockFor(a));

        pubsub.punsubscribe(a, "news.*"); // nothing left at all, but still connected
        assertSame(lock, pubsub.lockFor(a));

        pubsub.disconnected(a);
        assertNotSame(lock, pubsub.lockFor(a), "a closed connection should leave nothing behind");
    }

    @Test
    @DisplayName("PUBSUB NUMSUB counts a channel's own subscribers, not pattern ones")
    void numSubIgnoresPatterns() {
        pubsub.subscribe(a, "news");
        pubsub.psubscribe(b, "n*");

        assertEquals(1, pubsub.numSub("news"));
        assertEquals(0, pubsub.numSub("nothing"));
    }

    @Test
    @DisplayName("PUBSUB NUMPAT counts distinct patterns")
    void numPatCountsDistinctPatterns() {
        pubsub.psubscribe(a, "news.*");
        pubsub.psubscribe(b, "news.*");
        pubsub.psubscribe(b, "sport.*");

        assertEquals(2, pubsub.numPat());
    }

    @Test
    @DisplayName("PUBSUB CHANNELS lists channels with subscribers, optionally matching a pattern")
    void activeChannels() {
        pubsub.subscribe(a, "news.tech");
        pubsub.subscribe(b, "sport");
        pubsub.psubscribe(b, "weather.*"); // patterns are not channels

        assertEquals(List.of("news.tech", "sport"), pubsub.activeChannels(null).stream().sorted().toList());
        assertEquals(List.of("news.tech"), pubsub.activeChannels("news.*"));

        pubsub.unsubscribe(a, "news.tech");
        assertEquals(List.of("sport"), pubsub.activeChannels(null));
    }

    /**
     * Removing the last subscriber of a channel used to take two steps: remove it from the
     * channel's set, then drop the set if it looked empty. A subscribe from another
     * connection landing between the two went into the set just before it was dropped, and
     * that connection never received a message on the channel.
     */
    @Test
    @DisplayName("A subscribe racing another connection's last unsubscribe is not lost")
    void subscribeRacingLastUnsubscribe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier go = new CyclicBarrier(2);
            for (int round = 0; round < 5000; round++) {
                pubsub.subscribe(a, "c");
                Future<?> leaving = pool.submit(() -> {
                    go.await();
                    return pubsub.unsubscribe(a, "c");
                });
                Future<?> joining = pool.submit(() -> {
                    go.await();
                    return pubsub.subscribe(b, "c");
                });
                leaving.get(5, TimeUnit.SECONDS);
                joining.get(5, TimeUnit.SECONDS);

                assertEquals(1, pubsub.numSub("c"), "round " + round + ": b's subscription vanished");
                pubsub.unsubscribe(b, "c");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
