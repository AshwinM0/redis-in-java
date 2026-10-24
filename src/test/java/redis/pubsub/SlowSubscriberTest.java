package redis.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.resp.Protocol;

/**
 * A subscriber that stops reading, a chat server paused in a debugger say, must not hold up
 * anyone else. Publishers queue and move on; the stuck subscriber's queue grows until it
 * passes the limit, and then it is disconnected.
 */
class SlowSubscriberTest {

    /** 135 bytes as a frame on channel "news": *3, message, news, then 100 bytes of payload. */
    private static final String PAYLOAD = "x".repeat(100);
    private static final int FRAME_BYTES = 135;

    /**
     * A client that has stopped reading: the first write blocks until the connection is
     * closed, and then fails, as a write to a closed socket does.
     */
    static final class StuckStream extends OutputStream {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile boolean failWhenReleased = true;
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writing.countDown();
            try {
                // Bounded, so a regression that leaves a publisher stuck here fails the
                // run instead of keeping the test JVM alive for good.
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
            if (failWhenReleased) {
                throw new IOException("connection closed");
            }
            written.write(b, off, len);
        }

        /** The client starts reading again. */
        void release() {
            failWhenReleased = false;
            released.countDown();
        }

        /** Stands in for closing the socket: the blocked write fails. */
        @Override
        public void close() {
            closed.countDown();
            released.countDown();
        }

        String wire() {
            return written.toString(Protocol.WIRE);
        }
    }

    @Test
    @DisplayName("A subscriber that stops reading never stalls a publisher, and is cut off at the limit")
    void stuckSubscriberIsCutOff() throws Exception {
        PubSubManager pubsub = new PubSubManager(1000);
        StuckStream stuck = new StuckStream();
        pubsub.connect(stuck, stuck);
        pubsub.subscribe(stuck, "news");

        // The writer takes the first message off the queue and blocks writing it.
        AtomicInteger delivered = new AtomicInteger(pubsub.publish("news", PAYLOAD));
        assertTrue(stuck.writing.await(5, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 1; i < 100; i++) {
                delivered.addAndGet(pubsub.publish("news", PAYLOAD));
            }
        }, "a publisher waited on a subscriber that is not reading");

        // Seven frames fit in 1000 bytes. The first counts too, although the writer has
        // taken it off the queue: its write has not returned, so it is still in memory.
        assertEquals(1000 / FRAME_BYTES, delivered.get());
        assertTrue(stuck.closed.await(5, TimeUnit.SECONDS), "the stuck subscriber's connection was not closed");
        assertFalse(pubsub.isSubscribed(stuck));
        assertEquals(0, pubsub.numSub("news"));
        assertEquals(0, pubsub.publish("news", PAYLOAD));
    }

    @Test
    @DisplayName("A healthy subscriber keeps getting every message while another is stuck")
    void healthySubscriberUnaffected() throws Exception {
        PubSubManager pubsub = new PubSubManager(1000);
        StuckStream stuck = new StuckStream();
        ByteArrayOutputStream healthy = new ByteArrayOutputStream();
        pubsub.connect(stuck, stuck);
        pubsub.subscribe(stuck, "news");
        pubsub.subscribe(healthy, "news");

        // Each message reaches the healthy subscriber before the next is published, so its
        // queue never holds more than one. Racing ahead could fill even a healthy queue.
        int published = 50;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 1; i <= published; i++) {
                pubsub.publish("news", PAYLOAD);
                while (healthy.size() < i * FRAME_BYTES) {
                    Thread.onSpinWait();
                }
            }
        }, "the healthy subscriber stopped receiving");

        assertEquals(published * FRAME_BYTES, healthy.size());
        assertTrue(stuck.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("With no limit, a stuck subscriber is never cut off")
    void noLimit() throws Exception {
        PubSubManager pubsub = new PubSubManager(0);
        StuckStream stuck = new StuckStream();
        pubsub.connect(stuck, stuck);
        pubsub.subscribe(stuck, "news");

        for (int i = 0; i < 1000; i++) {
            assertEquals(1, pubsub.publish("news", PAYLOAD));
        }
        assertEquals(1, stuck.closed.getCount(), "cut off despite having no limit");
        assertTrue(pubsub.isSubscribed(stuck));
        stuck.close();
    }

    /**
     * UNSUBSCRIBE and the writer both run under the connection's lock. A message queued
     * before the unsubscribe but not yet written is dropped, so the client never receives a
     * message after being told the subscription has ended.
     */
    @Test
    @DisplayName("Messages still queued for a channel are dropped once it is unsubscribed")
    void queuedMessagesForEndedSubscriptionAreDropped() throws Exception {
        PubSubManager pubsub = new PubSubManager(0);
        StuckStream stuck = new StuckStream();
        pubsub.connect(stuck, stuck);
        pubsub.subscribe(stuck, "news");
        pubsub.subscribe(stuck, "marker");

        pubsub.publish("news", "first"); // the writer takes this one and blocks on it
        assertTrue(stuck.writing.await(5, TimeUnit.SECONDS));
        pubsub.publish("news", "second");
        pubsub.publish("news", "third");
        pubsub.unsubscribe(stuck, "news");
        pubsub.publish("marker", "end");

        stuck.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!stuck.wire().contains("end") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        // The queue is in order, so once the marker is out everything before it has been
        // written or dropped.
        String wire = stuck.wire();
        assertTrue(wire.contains("end"), wire);
        assertTrue(wire.contains("first"), "the message already being written should finish: " + wire);
        assertFalse(wire.contains("second") || wire.contains("third"), wire);
    }

    @Test
    @DisplayName("A write that fails cuts the subscriber off and closes its connection")
    void failedWriteClosesTheConnection() throws Exception {
        PubSubManager pubsub = new PubSubManager(0);
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client gone");
            }
        };
        CountDownLatch closed = new CountDownLatch(1);
        pubsub.connect(broken, closed::countDown);
        pubsub.subscribe(broken, "news");

        pubsub.publish("news", "m");

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertFalse(pubsub.isSubscribed(broken));
    }

    /**
     * A lingering close shuts the socket's output while the writer may be mid-write. That
     * write then fails, and it must not be taken for a broken connection: closing the
     * socket there would cut the lingering close short.
     */
    @Test
    @DisplayName("A closing connection is not closed again when its last write fails")
    void closingConnectionIsLeftToCloseItself() throws Exception {
        // The writer runs on the publishing thread, so joining that thread waits it out.
        PubSubManager pubsub = new PubSubManager(0, Runnable::run);
        StuckStream stuck = new StuckStream();
        AtomicInteger closes = new AtomicInteger();
        pubsub.connect(stuck, closes::incrementAndGet);
        pubsub.subscribe(stuck, "news");

        Thread publisher = new Thread(() -> pubsub.publish("news", "m"));
        publisher.start();
        assertTrue(stuck.writing.await(5, TimeUnit.SECONDS));

        pubsub.closing(stuck);
        stuck.close(); // the write fails
        publisher.join(5000);

        assertFalse(publisher.isAlive());
        assertEquals(0, closes.get(), "the subscriber was cut off while closing");
    }
}
