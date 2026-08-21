package redis.pubsub;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import static org.junit.jupiter.api.Assertions.*;


import org.junit.jupiter.api.*;

public class SubscriberTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should create subscriber with given OutputStream")
        void testConstructor() {
            ByteArrayOutputStream sw = new ByteArrayOutputStream();
            OutputStream bw = sw;
            Subscriber sub = new Subscriber(bw);

            assertNotNull(sub.out);
            assertNotNull(sub.lock);
            assertEquals(bw, sub.out);
        }

        @Test
        @DisplayName("should create unique lock object")
        void testUniqueLock() {
            ByteArrayOutputStream sw = new ByteArrayOutputStream();
            OutputStream bw = sw;
            Subscriber sub = new Subscriber(bw);

            assertNotNull(sub.lock);
            assertNotSame(bw, sub.lock);
        }
    }

    @Nested
    @DisplayName("Equals")
    class Equals {

        @Test
        @DisplayName("should return true for same instance")
        void testEqualsSameInstance() {
            ByteArrayOutputStream sw = new ByteArrayOutputStream();
            OutputStream bw = sw;
            Subscriber sub = new Subscriber(bw);

            assertTrue(sub.equals(sub));
        }

        @Test
        @DisplayName("should return true for subscribers with same OutputStream")
        void testEqualsSameWriter() {
            ByteArrayOutputStream sw = new ByteArrayOutputStream();
            OutputStream bw = sw;
            Subscriber sub1 = new Subscriber(bw);
            Subscriber sub2 = new Subscriber(bw);

            assertTrue(sub1.equals(sub2));
            assertTrue(sub2.equals(sub1));
        }

        @Test
        @DisplayName("should return false for subscribers with different OutputStreams")
        void testEqualsDifferentWriters() {
            OutputStream bw1 = new ByteArrayOutputStream();
            OutputStream bw2 = new ByteArrayOutputStream();
            Subscriber sub1 = new Subscriber(bw1);
            Subscriber sub2 = new Subscriber(bw2);

            assertFalse(sub1.equals(sub2));
        }

        @Test
        @DisplayName("should return false for null")
        void testEqualsNull() {
            OutputStream bw = new ByteArrayOutputStream();
            Subscriber sub = new Subscriber(bw);

            assertFalse(sub.equals(null));
        }
    }

    @Nested
    @DisplayName("HashCode")
    class HashCodeTests {

        @Test
        @DisplayName("should return same hashCode for equal subscribers")
        void testHashCodeConsistency() {
            ByteArrayOutputStream sw = new ByteArrayOutputStream();
            OutputStream bw = sw;
            Subscriber sub1 = new Subscriber(bw);
            Subscriber sub2 = new Subscriber(bw);

            assertEquals(sub1.hashCode(), sub2.hashCode());
        }

        @Test
        @DisplayName("should return same hashCode on multiple calls")
        void testHashCodeStable() {
            OutputStream bw = new ByteArrayOutputStream();
            Subscriber sub = new Subscriber(bw);

            int hash1 = sub.hashCode();
            int hash2 = sub.hashCode();

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("hashCode should be based on OutputStream")
        void testHashCodeBasedOnWriter() {
            OutputStream bw = new ByteArrayOutputStream();
            Subscriber sub = new Subscriber(bw);

            assertEquals(bw.hashCode(), sub.hashCode());
        }
    }
}
