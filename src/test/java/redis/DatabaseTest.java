package redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.*;

import redis.core.Database;
import redis.mocks.FakeClock;

public class DatabaseTest {

    @Nested
    @DisplayName("String store")
    class StringStore {
        Database db;
        FakeClock clock;

        @BeforeEach
        void beforeEach() {
            clock = new FakeClock(0);
            db = new Database(clock);
        }

        @Nested
        @DisplayName("SET methods")
        class Set {

            @Test
            @DisplayName("'set' should set the value in string store and check if it is present")
            void testSetAndGet() {
                db.set("foo", "bar");
                assertEquals("bar", db.get("foo"));
            }

            @Test
            @DisplayName("'set' should overwrite existing value in string store")
            void testOverwriteSet() {
                db.set("foo", "bar");
                db.set("foo", "baz");
                assertEquals("baz", db.get("foo"));
            }

            @Test
            @DisplayName("'setAndRemoveOlder' should set the value and remove older entries from other stores")
            void testSetAndRemoveOlder() {
                db.set("key", "value");
                db.expire("key", 10);

                db.setAndRemoveOlder("key", "newValue");
                assertEquals(-1, db.ttl("key"));
                assertEquals("newValue", db.get("key"));
            }

            @Test
            @DisplayName("'setAndRemoveOlder' should clear expiryMap entry if present")
            void testSetAndRemoveOlderRemovesExpiry() {
                db.set("x", "y");
                db.expire("x", 5);
                assertTrue(db.ttl("x") > 0);
                db.setAndRemoveOlder("x", "z");
                assertEquals(-1, db.ttl("x")); // expiry removed
                assertEquals("z", db.get("x"));
            }
        }

        @Nested
        @DisplayName("GET methods")
        class Get {

            @Test
            @DisplayName("'get' should return null for non-existing key")
            void testGetNonExistingKey() {
                assertEquals(null, db.get("nonexistent"));
            }

            @Test
            @DisplayName("'get' should return null for expired key")
            void testGetExpiredKey() throws InterruptedException {
                db.set("temp", "data");
                db.expire("temp", 1); // expire in 1 second
                clock.advanceSeconds(2);
                assertEquals(null, db.get("temp"));
            }

            @Test
            @DisplayName("'get' should return the correct value for existing key")
            void testGetExistingKey() {
                db.set("foo", "bar");
                assertEquals("bar", db.get("foo"));
            }

            @Test
            @DisplayName("'get' should remove expired key from the store")
            void testGetRemovesExpiredKey() throws InterruptedException {
                db.set("temp", "data");
                db.expire("temp", 1); // expire in 1 second
                clock.advanceSeconds(2);
                assertEquals(null, db.get("temp"));
                assertEquals(false, db.stringStoreContainsKey("temp"));
            }
        }

        @Nested
        @DisplayName("DEL methods")
        class Del {

            @Test
            @DisplayName("'del' should delete the key from string store")
            void testDelExistingKey() {
                db.set("foo", "bar");
                int result = db.del("foo");
                assertEquals(1, result);
                assertEquals(null, db.get("foo"));
            }

            @Test
            @DisplayName("'del' should return 0 when trying to delete a non-existing key")
            void testDelNonExistingKey() {
                int result = db.del("nonexistent");
                assertEquals(0, result);
            }

            @Test
            @DisplayName("'del' should remove expiry entry if present")
            void testDelRemovesExpiry() {
                db.set("temp", "123");
                db.expire("temp", 10);
                assertTrue(db.ttl("temp") > 0);
                db.del("temp");
                db.set("temp", "123");
                assertEquals(-1, db.ttl("temp")); // no expiry info anymore
            }
        }

        @Nested
        @DisplayName("KEY type methods")
        class KeyTypes {

            @Test
            @DisplayName("'getKeyType' should return 'string' for string keys")
            void testGetKeyTypeString() {
                db.set("foo", "bar");
                assertEquals("string", db.getKeyType("foo"));
            }

            @Test
            @DisplayName("'getKeyType' should return 'hash' for hash store keys")
            void testGetKeyTypeHash() {
                db.hset("hash1", "key", "val");
                db.hset("hash1", "key1", "val1");

                assertEquals("hash", db.getKeyType("hash1"));
            }

            @Test
            @DisplayName("'getKeyType' should return null for non-existing keys")
            void testGetKeyTypeNonExisting() {
                assertEquals(null, db.getKeyType("nonexistent"));
            }

            @Test
            @DisplayName("'ifHashKeyTypeMismatch' should return true if key is present in other store and not in Hast store")
            void testIfHashKeyTypeMismatchTrue() {
                db.set("foo", "bar");
                assertEquals(true, db.ifHashKeyTypeMismatch("foo"));
            }

            @Test
            @DisplayName("'ifHashKeyTypeMismatch' should return false if key is present Hast store")
            void testIfHashKeyTypeMismatchFalse() {
                db.hset("foo", "bar", "baz");
                assertEquals(false, db.ifHashKeyTypeMismatch("foo"));
            }

            @Test
            @DisplayName("string and hash keys should not interfere with each other")
            void testHashAndStringSeparation() {
                db.set("mykey", "val");
                db.hset("myhash", "field", "fval");

                assertEquals("val", db.get("mykey"));
                assertEquals("fval", db.hashget("myhash", "field"));
                assertEquals("string", db.getKeyType("mykey"));
                assertEquals("hash", db.getKeyType("myhash"));
            }
        }

        @Nested
        @DisplayName("EXPIRE and TTL methods")
        class ExpireAndTTL {

            @Test
            @DisplayName("'expire' should set expiry for existing key and return true")
            void testExpireExistingKey() {
                db.set("foo", "bar");
                boolean result = db.expire("foo", 10);
                assertEquals(true, result);
            }

            @Test
            @DisplayName("'expire' should return false for non-existing key")
            void testExpireNonExistingKey() {
                boolean result = db.expire("nonexistent", 10);
                assertEquals(false, result);
            }

            @Test
            @DisplayName("'TTL' should return -1 for persistent key")
            void testPersistentTTL() {
                Database db = new Database();
                db.set("a", "b");
                assertEquals(-1, db.ttl("a"));
            }

            @Test
            @DisplayName("db should expire a key after its TTL")
            void testExpireAndTTL() throws InterruptedException {
                Database db = new Database();
                db.set("temp", "123");
                db.expire("temp", 1);
                assertEquals(1, db.ttl("temp"));
                clock.advanceSeconds(2);
                Thread.sleep(1000);
                assertEquals(-2, db.ttl("temp"));
                assertEquals(null, db.get("temp"));
                assertEquals(false, db.stringStoreContainsKey("temp"));
            }

            @Test
            @DisplayName("'expire' should overwrite existing expiry time")
            void testExpireOverwritesTTL() throws InterruptedException {
                db.set("foo", "bar");
                db.expire("foo", 1);
                clock.advanceMillis(500);
                db.expire("foo", 3);
                assertTrue(db.ttl("foo") > 1);
            }

            @Test
            @DisplayName("'expire' with 0 seconds should delete key immediately")
            void testExpireZeroDeletesImmediately() {
                db.set("foo", "bar");
                boolean result = db.expire("foo", 0);
                assertTrue(result);
                assertEquals(null, db.get("foo"));
            }

            @Test
            @DisplayName("'expire' with negative seconds should delete key immediately")
            void testExpireNegativeDeletesImmediately() {
                db.set("foo", "bar");
                boolean result = db.expire("foo", -5);
                assertTrue(result);
                assertEquals(null, db.get("foo"));
            }

            @Test
            @DisplayName("'ttl' should return -2 if expiry time already passed by 1ms margin")
            void testTTLPrecisionEdge() {
                db.set("foo", "bar");
                db.expire("foo", 0);
                assertEquals(-2, db.ttl("foo"));
            }

            @Test
            @DisplayName("expired key should not return even if re-fetched")
            void testExpiredKeyNotRevived() throws InterruptedException {
                db.set("ghost", "boo");
                db.expire("ghost", 1);
                clock.advanceSeconds(2);
                assertEquals(null, db.get("ghost"));
                assertEquals(null, db.get("ghost")); // repeat call doesn’t revive it
            }
        }
    }

    @Nested
    @DisplayName("Hash Store")
    class HashStore {
        Database db;
        FakeClock clock;

        @BeforeEach
        void beforeEach() {
            clock = new FakeClock(0);
            db = new Database(clock);
        }

        @Nested
        @DisplayName("HSET")
        class HSET {

            @Test
            @DisplayName("'hset' should return 1 for a new field in a new hash key")
            void testHSetNewHashNewField() {
                int val = db.hset("myhash", "f1", "v1");
                assertEquals(1, val);
                assertEquals("v1", db.hashget("myhash", "f1"));
            }

            @Test
            @DisplayName("'hset' should return 0 when updating existing field")
            void testHSetUpdateExistingField() {
                db.hset("myhash", "field1", "value1");
                int val = db.hset("myhash", "field1", "value2");
                assertEquals(val, 0);
                assertEquals("value2", db.hashget("myhash", "field1"));
            }

            @Test
            @DisplayName("'hset' and 'hget' should set and get values in hash store")
            void testHSetAndHGet() {
                int val = db.hset("myhash", "field1", "value1");
                assertEquals(val, 1);
                assertEquals("value1", db.hashget("myhash", "field1"));
            }

            @Test
            @DisplayName("'hset' should overwrite existing field value in hash store")
            void testHSetOverwrite() {
                int val1 = db.hset("myhash", "field1", "value1");
                int val2 = db.hset("myhash", "field1", "value2");
                assertEquals(val1, 1);
                assertEquals(val2, 0);
                assertEquals("value2", db.hashget("myhash", "field1"));
            }

            @Test
            @DisplayName("'hset' should allow multiple fields per hash key")
            void testHSetMultipleFields() {
                db.hset("myhash", "f1", "v1");
                db.hset("myhash", "f2", "v2");
                db.hset("myhash", "f3", "v3");

                assertEquals("v1", db.hashget("myhash", "f1"));
                assertEquals("v2", db.hashget("myhash", "f2"));
                assertEquals("v3", db.hashget("myhash", "f3"));
            }

            @Test
            @DisplayName("'ifHashKeyTypeMismatch' should detect that a string key cannot be used as a hash key")
            void testIfHashKeyTypeMismatchDetectsStringKey() {
                db.set("key", "simple");

                // This is the only layer that should detect mismatch
                boolean mismatch = db.ifHashKeyTypeMismatch("key");
                assertTrue(mismatch, "Expected a type mismatch for string key used in hash command");
            }
        }

        @Nested
        @DisplayName("HGET")
        class HGET {

            @Test
            @DisplayName("'hget' should return null for non-existing hash key")
            void testHGetNonExistingHashKey() {
                assertEquals(null, db.hashget("nonexistent", "field1"));
            }

            @Test
            @DisplayName("'hget' should return null for non-existing field in existing hash")
            void testHGetNonExistingField() {
                db.hset("myhash", "field1", "value1");
                assertEquals(null, db.hashget("myhash", "field2"));
            }

            @Test
            @DisplayName("'hget' should return correct value for existing field in hash")
            void testHGetExistingField() {
                db.hset("myhash", "field1", "value1");
                assertEquals("value1", db.hashget("myhash", "field1"));
            }

            @Test
            @DisplayName("'hget' should return latest updated value")
            void testHGetLatestValue() {
                db.hset("h", "f", "v1");
                db.hset("h", "f", "v2");
                assertEquals("v2", db.hashget("h", "f"));
            }
        }

        @Nested
        @DisplayName("HDEL")
        class HDEL {

            @Test
            @DisplayName("'hdel' should delete a field and return 1")
            void testHDelSingleField() {
                db.hset("myhash", "f1", "v1");
                int result = db.deleteHashField("myhash", "f1");
                assertEquals(1, result);
                assertEquals(null, db.hashget("myhash", "f1"));
            }

            @Test
            @DisplayName("'hdel' should return 0 when deleting non-existing field")
            void testHDelNonExistingField() {
                db.hset("myhash", "f1", "v1");
                int result = db.deleteHashField("myhash", "f2");
                assertEquals(0, result);
            }

            @Test
            @DisplayName("'hdel' should return 0 when deleting from non-existing hash")
            void testHDelNonExistingHash() {
                assertEquals(0, db.deleteHashField("nohash", "f1"));
            }

            @Test
            @DisplayName("'hdel' should remove the hash key when all fields are deleted")
            void testHDelRemovesHashCompletely() {
                db.hset("myhash", "f1", "v1");
                db.hset("myhash", "f2", "v2");

                db.deleteHashField("myhash", "f1");
                db.deleteHashField("myhash", "f2");

                assertEquals(false, db.containsHashKey("myhash"));
            }
        }

        @Nested
        @DisplayName("HGETALL")
        class HGETALL {

            @Test
            @DisplayName("'hgetall' should return empty list for non-existing hash key")
            void testHGetAllNonExistingHash() {
                assertEquals(true, db.getAllHashEntries("missing").isEmpty());
            }

            @Test
            @DisplayName("'hgetall' should return all field-value pairs correctly")
            void testHGetAllReturnsAllEntries() {
                db.hset("myhash", "f1", "v1");
                db.hset("myhash", "f2", "v2");
                db.hset("myhash", "f3", "v3");

                var entries = db.getAllHashEntries("myhash");

                assertEquals(3, entries.size());
                assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("f1") && e.getValue().equals("v1")));
                assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("f2") && e.getValue().equals("v2")));
                assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("f3") && e.getValue().equals("v3")));
            }
        }

        @Nested
        @DisplayName("TTL on Hash Keys")
        class TTL {

            @Test
            @DisplayName("should respect expiry set via EXPIRE")
            void testHashExpiryWorks() throws InterruptedException {
                db.hset("user:1", "name", "madavan");
                db.expire("user:1", 1); // expire in 1 second
                assertTrue(db.ttl("user:1") <= 1);
                clock.advanceSeconds(2);
                assertEquals(-2, db.ttl("user:1")); // expired
                assertEquals(null, db.hashget("user:1", "name"));
            }

            @Test
            @DisplayName("should return -1 for persistent hash")
            void testHashPersistentTTL() {
                db.hset("myhash", "k", "v");
                assertEquals(-1, db.ttl("myhash"));
            }
        }

        @Nested
        @DisplayName("HSETNX")
        class HSETNX {
            @Test
            @DisplayName("should set value if field does not exist")
            void testHSetNxNewField() {
                int res = db.hsetnx("h", "f", "v");
                assertEquals(1, res);
                assertEquals("v", db.hashget("h", "f"));
            }

            @Test
            @DisplayName("should not set value if field exists")
            void testHSetNxExistingField() {
                db.hset("h", "f", "v1");
                int res = db.hsetnx("h", "f", "v2");
                assertEquals(0, res);
                assertEquals("v1", db.hashget("h", "f"));
            }
        }

        @Nested
        @DisplayName("HEXISTS")
        class HEXISTS {
            @Test
            @DisplayName("should return 1 if field exists")
            void testHExistsTrue() {
                db.hset("h", "f", "v");
                assertEquals(1, db.hexists("h", "f"));
            }

            @Test
            @DisplayName("should return 0 if field does not exist")
            void testHExistsFalse() {
                db.hset("h", "f", "v");
                assertEquals(0, db.hexists("h", "missing"));
            }
        }

        @Nested
        @DisplayName("HLEN")
        class HLEN {
            @Test
            @DisplayName("should return correct number of fields")
            void testHLen() {
                db.hset("h", "f1", "v1");
                db.hset("h", "f2", "v2");
                assertEquals(2, db.hlen("h"));
            }

            @Test
            @DisplayName("should return 0 for non-existing hash")
            void testHLenMissing() {
                assertEquals(0, db.hlen("missing"));
            }
        }

        @Nested
        @DisplayName("Delete Hash Key")
        class DeleteHashKey {
            @Test
            @DisplayName("should remove entire hash")
            void testDeleteHashKey() {
                db.hset("h", "f", "v");
                assertEquals(1, db.deleteHashKey("h"));
                assertEquals(false, db.containsHashKey("h"));
            }
        }

    } // End HashStore

    @Nested
    @DisplayName("General Commands")
    class GeneralCommands {
        Database db;

        @BeforeEach
        void beforeEach() {
            db = new Database();
        }

        @Test
        @DisplayName("keyExists should return true for string or hash keys")
        void testKeyExists() {
            db.set("s", "val");
            db.hset("h", "f", "v");

            assertTrue(db.keyExists("s"));
            assertTrue(db.keyExists("h"));
            assertEquals(false, db.keyExists("missing"));
        }

        @Test
        @DisplayName("flushAll should clear everything")
        void testFlushAll() {
            db.set("s", "val");
            db.hset("h", "f", "v");
            db.expire("s", 100);

            db.flushAll();

            assertEquals(null, db.get("s"));
            assertEquals(false, db.containsHashKey("h"));
            assertEquals(-2, db.ttl("s")); // fully gone
        }

        @Test
        @DisplayName("getKeysMatching should support wildcards")
        void testKeysMatching() {
            db.set("user:1", "a");
            db.set("user:2", "b");
            db.set("post:1", "c");

            var keys = db.getKeysMatching("user:*");
            assertEquals(2, keys.size());
            assertTrue(keys.contains("user:1"));
            assertTrue(keys.contains("user:2"));
        }
    }
}
