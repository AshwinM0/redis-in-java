package redis.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.resp.Protocol;

/**
 * A subscribed connection is written to by two kinds of thread: its own, replying to
 * commands, and any thread delivering a published message. They have to agree on a lock.
 */
class PubSubWriteSafetyTest {

    @Test
    @DisplayName("Every channel a connection subscribes to shares that connection's lock")
    void oneLockPerConnectionNotPerSubscription() {
        PubSubManager ps = new PubSubManager();
        OutputStream client = new ByteArrayOutputStream();

        ps.subscribe(client, "alpha");
        ps.subscribe(client, "beta");

        // A lock per subscription would give publishers to alpha and beta different
        // monitors over the same socket, and they would interleave.
        assertSame(ps.lockFor(client), ps.lockFor(client));
    }

    @Test
    @DisplayName("Concurrent publishes to different channels do not interleave in one socket")
    void concurrentPublishesProduceWellFormedFrames() throws Exception {
        PubSubManager ps = new PubSubManager();

        // A stream that writes one byte at a time with a yield, so any unsynchronised
        // interleaving is near-certain to be observed rather than merely possible.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream slow = new OutputStream() {
            @Override
            public synchronized void write(int b) {
                sink.write(b);
                Thread.yield();
            }
        };

        ps.subscribe(slow, "alpha");
        ps.subscribe(slow, "beta");

        int perChannel = 150;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        for (String channel : new String[] { "alpha", "beta" }) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < perChannel; i++) {
                    ps.publish(channel, "payload-" + i);
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        String wire = sink.toString(Protocol.WIRE);
        assertEquals(2 * perChannel, countWellFormedMessages(wire),
            "frames were spliced together; a reply and a message shared the socket unsynchronised");
    }

    @Test
    @DisplayName("Two clients publishing to each other's channels do not deadlock")
    void mutualPublishDoesNotDeadlock() throws Exception {
        PubSubManager ps = new PubSubManager();
        OutputStream a = new ByteArrayOutputStream();
        OutputStream b = new ByteArrayOutputStream();

        ps.subscribe(a, "to-a");
        ps.subscribe(b, "to-b");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);

        // Each side holds its own connection lock and then publishes to the other. If
        // delivery held the publisher's lock too, this is the cycle that would hang.
        pool.submit(() -> {
            go.await();
            for (int i = 0; i < 500; i++) {
                synchronized (ps.lockFor(a)) {
                    // stands in for this connection writing its own reply
                }
                ps.publish("to-b", "ping");
            }
            return null;
        });
        pool.submit(() -> {
            go.await();
            for (int i = 0; i < 500; i++) {
                synchronized (ps.lockFor(b)) {
                }
                ps.publish("to-a", "pong");
            }
            return null;
        });

        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "publishers deadlocked");
    }

    @Test
    @DisplayName("A broken connection is reaped from every channel, not just the failing one")
    void deadClientIsRemovedEverywhere() {
        PubSubManager ps = new PubSubManager();
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client gone");
            }
        };

        ps.subscribe(broken, "c1");
        ps.subscribe(broken, "c2");

        assertEquals(0, ps.publish("c1", "m"), "a failed write still counted as delivered");

        // Previously the client was dropped from c1 only, and stayed registered on c2
        // where every later publish would retry it for the life of the process.
        assertFalse(ps.isSubscribed(broken));
        assertEquals(0, ps.publish("c2", "m"));
    }

    /** Counts complete ["message", channel, payload] frames, rejecting spliced ones. */
    private static int countWellFormedMessages(String wire) {
        int count = 0;
        int i = 0;
        while (true) {
            int next = wire.indexOf("*3\r\n$7\r\nmessage\r\n", i);
            if (next < 0) {
                return count;
            }
            // Nothing may sit between the end of one frame and the start of the next.
            if (next != i) {
                return -1;
            }
            int end = wire.indexOf("\r\n", wire.indexOf("payload-", next));
            if (end < 0) {
                return -1;
            }
            count++;
            i = end + 2;
        }
    }
}
