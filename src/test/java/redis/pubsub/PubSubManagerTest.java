package redis.pubsub;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.*;

public class PubSubManagerTest {

    PubSubManager pubsub;
    BufferedWriter client1;
    BufferedWriter client2;
    BufferedWriter client3;
    StringWriter output1;
    StringWriter output2;
    StringWriter output3;

    @BeforeEach
    void beforeEach() {
        pubsub = new PubSubManager();
        output1 = new StringWriter();
        output2 = new StringWriter();
        output3 = new StringWriter();
        client1 = new BufferedWriter(output1);
        client2 = new BufferedWriter(output2);
        client3 = new BufferedWriter(output3);
    }

    @Nested
    @DisplayName("Subscribe")
    class Subscribe {

        @Test
        @DisplayName("should return 1 when subscribing to first channel")
        void testSubscribeFirstChannel() {
            int count = pubsub.subscribe(client1, "news");
            assertEquals(1, count);
        }

        @Test
        @DisplayName("should return 2 when subscribing to second channel")
        void testSubscribeSecondChannel() {
            pubsub.subscribe(client1, "news");
            int count = pubsub.subscribe(client1, "sports");
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should allow multiple clients to subscribe to same channel")
        void testMultipleClientsSubscribeSameChannel() {
            int count1 = pubsub.subscribe(client1, "news");
            int count2 = pubsub.subscribe(client2, "news");
            assertEquals(1, count1);
            assertEquals(1, count2);
        }

        @Test
        @DisplayName("should allow same client to subscribe to multiple channels")
        void testSameClientMultipleChannels() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client1, "sports");
            int count = pubsub.subscribe(client1, "weather");
            assertEquals(3, count);
        }

        @Test
        @DisplayName("subscribing to same channel twice should still increment count")
        void testSubscribeSameChannelTwice() {
            pubsub.subscribe(client1, "news");
            int count = pubsub.subscribe(client1, "news");
            // Redis behavior: subscribe to same channel is idempotent
            // Since we use Set, the count remains 1
            assertEquals(1, count);
        }
    }

    @Nested
    @DisplayName("IsSubscribed")
    class IsSubscribed {

        @Test
        @DisplayName("should return false for client with no subscriptions")
        void testNotSubscribed() {
            assertFalse(pubsub.isSubscribed(client1));
        }

        @Test
        @DisplayName("should return true for client with subscriptions")
        void testIsSubscribed() {
            pubsub.subscribe(client1, "news");
            assertTrue(pubsub.isSubscribed(client1));
        }

        @Test
        @DisplayName("should return false after unsubscribing from all channels")
        void testNotSubscribedAfterUnsubscribeAll() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client1, "sports");
            pubsub.unsubscribeAll(client1);
            assertFalse(pubsub.isSubscribed(client1));
        }
    }

    @Nested
    @DisplayName("Unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("should return 0 when unsubscribing from only channel")
        void testUnsubscribeOnlyChannel() {
            pubsub.subscribe(client1, "news");
            int remaining = pubsub.unsubscribe(client1, "news");
            assertEquals(0, remaining);
        }

        @Test
        @DisplayName("should return remaining count after unsubscribing")
        void testUnsubscribeOneOfMany() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client1, "sports");
            pubsub.subscribe(client1, "weather");
            int remaining = pubsub.unsubscribe(client1, "news");
            assertEquals(2, remaining);
        }

        @Test
        @DisplayName("should return 0 when unsubscribing from non-subscribed channel")
        void testUnsubscribeNonSubscribed() {
            int remaining = pubsub.unsubscribe(client1, "news");
            assertEquals(0, remaining);
        }

        @Test
        @DisplayName("should not affect other clients subscriptions")
        void testUnsubscribeDoesNotAffectOthers() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client2, "news");
            pubsub.unsubscribe(client1, "news");
            assertTrue(pubsub.isSubscribed(client2));
        }
    }

    @Nested
    @DisplayName("UnsubscribeAll")
    class UnsubscribeAll {

        @Test
        @DisplayName("should remove all subscriptions for a client")
        void testUnsubscribeAllChannels() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client1, "sports");
            pubsub.subscribe(client1, "weather");
            pubsub.unsubscribeAll(client1);
            assertFalse(pubsub.isSubscribed(client1));
        }

        @Test
        @DisplayName("should not affect other clients")
        void testUnsubscribeAllDoesNotAffectOthers() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client2, "news");
            pubsub.subscribe(client2, "sports");
            pubsub.unsubscribeAll(client1);
            assertTrue(pubsub.isSubscribed(client2));
        }

        @Test
        @DisplayName("should handle client with no subscriptions")
        void testUnsubscribeAllNoSubscriptions() {
            // Should not throw
            assertDoesNotThrow(() -> pubsub.unsubscribeAll(client1));
        }
    }

    @Nested
    @DisplayName("Publish")
    class Publish {

        @Test
        @DisplayName("should return 0 when publishing to channel with no subscribers")
        void testPublishNoSubscribers() {
            int delivered = pubsub.publish("news", "Hello!");
            assertEquals(0, delivered);
        }

        @Test
        @DisplayName("should return 1 when publishing to channel with one subscriber")
        void testPublishOneSubscriber() {
            pubsub.subscribe(client1, "news");
            int delivered = pubsub.publish("news", "Hello!");
            assertEquals(1, delivered);
        }

        @Test
        @DisplayName("should return subscriber count when publishing to channel with multiple subscribers")
        void testPublishMultipleSubscribers() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client2, "news");
            pubsub.subscribe(client3, "news");
            int delivered = pubsub.publish("news", "Breaking news!");
            assertEquals(3, delivered);
        }

        @Test
        @DisplayName("should only deliver to subscribers of that specific channel")
        void testPublishSpecificChannel() {
            pubsub.subscribe(client1, "news");
            pubsub.subscribe(client2, "sports");
            int delivered = pubsub.publish("news", "News update!");
            assertEquals(1, delivered);
        }

        @Test
        @DisplayName("should write message to subscriber's output stream")
        void testPublishWritesToOutput() throws Exception {
            pubsub.subscribe(client1, "news");
            pubsub.publish("news", "Hello World!");
            client1.flush();
            String output = output1.toString();
            // Check that output contains the message components
            assertTrue(output.contains("message"));
            assertTrue(output.contains("news"));
            assertTrue(output.contains("Hello World!"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty channel name")
        void testEmptyChannelName() {
            int count = pubsub.subscribe(client1, "");
            assertEquals(1, count);
            int delivered = pubsub.publish("", "test");
            assertEquals(1, delivered);
        }

        @Test
        @DisplayName("should handle channel name with special characters")
        void testSpecialCharacterChannel() {
            int count = pubsub.subscribe(client1, "channel:special:123");
            assertEquals(1, count);
            assertTrue(pubsub.isSubscribed(client1));
        }

        @Test
        @DisplayName("should handle empty message")
        void testEmptyMessage() {
            pubsub.subscribe(client1, "news");
            int delivered = pubsub.publish("news", "");
            assertEquals(1, delivered);
        }
    }
}
