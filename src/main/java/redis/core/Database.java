package redis.core;

import redis.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import java.util.logging.Level;
import java.util.logging.Logger;

import redis.time.SystemClock;
import redis.utils.GlobMatcher;

public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    public static final String WRONG_TYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_AN_INTEGER = "value is not an integer or out of range";
    private static final String NOT_A_FLOAT = "value is not a valid float";
    private static final String OVERFLOW = "increment or decrement would overflow";
    private static final String INVALID_EXPIRE = "invalid expire time";

    /** How many keys the active-expiry cycle samples per pass. */
    private static final int EXPIRY_SAMPLE_SIZE = 20;
    /** Keep sampling while more than this share of a pass turned out to be expired. */
    private static final double EXPIRY_CONTINUE_RATIO = 0.25;
    /** Upper bound on passes, so one cycle can never monopolise the cleaner thread. */
    private static final int EXPIRY_MAX_PASSES = 16;

    private ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();

    private final Clock clock;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> hashStore = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "expiry-cleaner");
        t.setDaemon(true);
        return t;
    });

    public Database() {
        this(new SystemClock());
    }

    public Database(Clock clock) {
        this.clock = clock;
        // Background thread to clean expired keys every 1 seconds
        cleaner.scheduleAtFixedRate(this::runExpiryCycle, 5, 1, TimeUnit.SECONDS);
    }

    /**
     * Entry point for the scheduled cleaner.
     *
     * <p>{@code scheduleAtFixedRate} cancels a task permanently, and silently, the first
     * time it throws. Swallowing here keeps active expiration alive for the life of the
     * process no matter what one cycle runs into.
     */
    private void runExpiryCycle() {
        try {
            removeSampledKeysIfExpired();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Expiry cycle failed", e);
        }
    }

    // Package-private so tests can drive a cycle directly instead of waiting on the
    // scheduler.
    void removeSampledKeysIfExpired() {
        for (int pass = 0; pass < EXPIRY_MAX_PASSES; pass++) {
            long now = clock.nowMillis();

            // Re-sample on every pass. The previous pass deleted what it found, so
            // reusing its snapshot would keep counting the same keys as expired.
            List<Map.Entry<String, Long>> sample = getRandomExpiryEntries(EXPIRY_SAMPLE_SIZE);
            if (sample.isEmpty()) {
                return;
            }

            int expired = 0;
            for (Map.Entry<String, Long> e : sample) {
                if (now > e.getValue()) {
                    expired++;
                    removeExpiredKey(e.getKey());
                }
            }

            // A mostly clean sample means the keyspace is mostly clean; wait for the
            // next tick rather than scanning further.
            if (expired / (double) sample.size() <= EXPIRY_CONTINUE_RATIO) {
                return;
            }
        }
    }

    private synchronized void removeExpiredKey(String key) {
        String type = getKeyType(key);
        if ("string".equals(type)) {
            store.remove(key);
        } else if ("hash".equals(type)) {
            hashStore.remove(key);
        }
        expiryMap.remove(key);
    }

    private List<Map.Entry<String, Long>> getRandomExpiryEntries(int count) {
        List<Map.Entry<String, Long>> entries = expiryMap.entrySet()
                .stream()
                .collect(Collectors.toList());

        int n = entries.size();
        if (n <= count)
            return entries;

        Random rand = ThreadLocalRandom.current();
        return rand.ints(0, n)
                .distinct()
                .limit(count)
                .mapToObj(entries::get)
                .collect(Collectors.toList());
    }

    /**
     * The single lazy-expiry gate. Drops {@code key} from every store if its deadline has
     * passed, and reports whether it did.
     *
     * <p>Every public method that reads a key, tests for its presence, or asks about its
     * type calls this first. Reading a store directly instead lets a key that is logically
     * dead still answer {@code TYPE}, satisfy an existence check, or — worst — be revived
     * by a write that merges into its leftover value.
     *
     * <p>Expiry is {@code now > deadline}, matching Redis's {@code keyIsExpired}. Every
     * read goes through here, so {@code TTL} and {@code GET} can no longer disagree about
     * the boundary millisecond. ({@link #expireAt} deliberately uses the other boundary
     * when <em>setting</em> a deadline; see the note there.)
     */
    private boolean reapIfExpired(String key) {
        Long deadline = expiryMap.get(key);
        if (deadline == null || clock.nowMillis() <= deadline) {
            return false;
        }
        expiryMap.remove(key);
        store.remove(key);
        hashStore.remove(key);
        return true;
    }

    /** True if the key is present in any store, after expiry has been applied. */
    private boolean liveKey(String key) {
        reapIfExpired(key);
        return store.containsKey(key) || hashStore.containsKey(key);
    }

    private boolean matchesGlob(String text, String pattern) {
        return GlobMatcher.matches(pattern, text);
    }

    public synchronized String getKeyType(String key) {
        reapIfExpired(key);
        if (store.containsKey(key))
            return "string";
        if (hashStore.containsKey(key))
            return "hash";
        return null;
    }

    public synchronized void setAndRemoveOlder(String key, String value) {
        if (store != null)
            store.remove(key);
        if (hashStore != null)
            hashStore.remove(key);

        store.put(key, value);
        expiryMap.remove(key); // Remove any old expiry
        return;
    }

    public synchronized void set(String key, String value) {
        store.put(key, value);
        return;
    }

    public synchronized String get(String key) {
        reapIfExpired(key);
        return store.get(key);
    }

    public synchronized int del(String key) {
        reapIfExpired(key);
        boolean removed = false;
        if (store.remove(key) != null)
            removed = true;

        if (hashStore.remove(key) != null)
            removed = true;

        expiryMap.remove(key);
        return removed ? 1 : 0;
    }

    public synchronized boolean stringStoreContainsKey(String key) {
        reapIfExpired(key);
        return store.containsKey(key);
    }

    /** EXPIRE, in whole seconds. */
    public synchronized boolean expire(String key, long seconds) {
        long millis;
        try {
            millis = Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException e) {
            throw new CommandException(INVALID_EXPIRE);
        }
        return pexpire(key, millis);
    }

    /** PEXPIRE: a relative deadline in milliseconds. */
    public synchronized boolean pexpire(String key, long millis) {
        long deadline;
        try {
            deadline = Math.addExact(clock.nowMillis(), millis);
        } catch (ArithmeticException e) {
            throw new CommandException(INVALID_EXPIRE);
        }
        return expireAt(key, deadline);
    }

    /**
     * PEXPIREAT: an absolute deadline in epoch milliseconds.
     *
     * <p>This is the form the AOF stores. A deadline is a fact about a moment in time, so
     * it replays to the same answer no matter when the replay happens; a relative TTL
     * would be recomputed against the restart clock and hand a long-dead key a fresh life.
     */
    public synchronized boolean expireAt(String key, long deadlineMillis) {
        if (!liveKey(key)) {
            return false;
        }
        // Setting a deadline that is already reached deletes the key, so EXPIRE k 0 takes
        // effect now rather than leaving the key alive for its final millisecond. Note the
        // boundary differs from the lazy gate's on purpose: Redis deletes on `when <= now`
        // here but only considers a stored key dead once `now > when`.
        if (deadlineMillis <= clock.nowMillis()) {
            del(key);
            return true;
        }
        expiryMap.put(key, deadlineMillis);
        return true;
    }

    public synchronized boolean keyExists(String key) {
        return liveKey(key);
    }

    /** TTL: whole seconds remaining, -1 for no expiry, -2 for no key. */
    public synchronized long ttl(String key) {
        long millis = pttl(key);
        if (millis < 0) {
            return millis; // -1 / -2 pass through unchanged
        }
        return (millis + 500) / 1000; // Redis rounds to the nearest second
    }

    /** PTTL: milliseconds remaining, -1 for no expiry, -2 for no key. */
    public synchronized long pttl(String key) {
        if (!liveKey(key)) {
            return -2;
        }
        Long deadline = expiryMap.get(key);
        if (deadline == null) {
            return -1;
        }
        return deadline - clock.nowMillis();
    }

    public synchronized int hset(String hashKey, String field, String value) {
        // Without this an expired hash is still sitting in hashStore, so the new field
        // merges into it and every field the key held before its TTL lapsed comes back.
        reapIfExpired(hashKey);
        hashStore.putIfAbsent(hashKey, new ConcurrentHashMap<>());
        String present = hashStore.get(hashKey).put(field, value);

        return present == null ? 1 : 0;
    }

    public synchronized int hsetnx(String key, String field, String value) {
        reapIfExpired(key);
        Map<String, String> map = hashStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        if (map.containsKey(field))
            return 0;

        map.put(field, value);
        return 1;
    }

    public synchronized int hexists(String key, String field) {
        reapIfExpired(key);
        Map<String, String> map = hashStore.get(key);
        return (map != null && map.containsKey(field)) ? 1 : 0;
    }

    public synchronized int hlen(String key) {
        reapIfExpired(key);
        Map<String, String> map = hashStore.get(key);
        return (map == null) ? 0 : map.size();
    }

    public synchronized boolean containsHashKey(String hashKey) {
        reapIfExpired(hashKey);
        return hashStore.containsKey(hashKey);
    }

    public synchronized String hashget(String hashKey, String field) {
        reapIfExpired(hashKey);
        Map<String, String> map = hashStore.get(hashKey);

        if (map == null)
            return null;
        return map.get(field);
    }

    public synchronized List<Map.Entry<String, String>> getAllHashEntries(String hashKey) {
        reapIfExpired(hashKey);
        Map<String, String> map = hashStore.get(hashKey);
        if (map == null) {
            return List.of(); // empty list if not found
        }

        return new ArrayList<>(map.entrySet());
    }

    public synchronized int deleteHashField(String hashKey, String field) {
        reapIfExpired(hashKey);
        if (!hashStore.containsKey(hashKey))
            return 0;
        Map<String, String> map = hashStore.get(hashKey);

        String removed = map.remove(field);
        if (map.isEmpty()) {
            hashStore.remove(hashKey);
        }
        return removed != null ? 1 : 0;
    }

    public synchronized int deleteHashKey(String hashKey) {
        reapIfExpired(hashKey);
        if (!hashStore.containsKey(hashKey))
            return 0;
        hashStore.remove(hashKey);
        return 1;
    }

    public synchronized boolean ifHashKeyTypeMismatch(String hashKey) {
        String type = getKeyType(hashKey);
        return type != null && !"hash".equals(type);
    }

    public synchronized Long getExpiry(String key) {
        reapIfExpired(key);
        return expiryMap.get(key);
    }

    public Clock getClock() {
        return clock;
    }

    public void setExpiry(String key, Long expiryTimeMillis) {
        expiryMap.put(key, expiryTimeMillis);
    }

    public synchronized List<String> getKeysMatching(String pattern) {
        List<String> result = new ArrayList<>();

        // include expired cleanup
        for (String key : new HashSet<>(store.keySet())) {
            if (ttl(key) >= -1 && matchesGlob(key, pattern)) {
                result.add(key);
            }
        }

        for (String key : new HashSet<>(hashStore.keySet())) {
            if (ttl(key) >= -1 && matchesGlob(key, pattern)) {
                result.add(key);
            }
        }

        return result;
    }

    public synchronized void flushAll() {
        store.clear();
        hashStore.clear();
        expiryMap.clear();
    }

    // ------------------------------------------------------------------
    // Atomic read-modify-write operations.
    //
    // Each of these does its read, its decision and its write inside one lock.
    // Splitting them across separate calls lets two threads read the same value
    // and both write back, losing one of the updates.
    // ------------------------------------------------------------------

    private void requireNotHash(String key) {
        reapIfExpired(key);
        if (hashStore.containsKey(key)) {
            throw new CommandException(WRONG_TYPE);
        }
    }

    private long currentAsLong(String key) {
        String current = get(key);
        if (current == null) {
            return 0;
        }
        try {
            return Long.parseLong(current);
        } catch (NumberFormatException e) {
            throw new CommandException(NOT_AN_INTEGER);
        }
    }

    /** INCR / DECR / INCRBY / DECRBY. Returns the value after the change. */
    public synchronized long incrBy(String key, long delta) {
        requireNotHash(key);
        long result;
        try {
            result = Math.addExact(currentAsLong(key), delta);
        } catch (ArithmeticException e) {
            throw new CommandException(OVERFLOW);
        }
        store.put(key, Long.toString(result));
        return result;
    }

    /** INCRBYFLOAT. Returns the value after the change. */
    public synchronized double incrByFloat(String key, double delta) {
        requireNotHash(key);
        String current = get(key);
        double base;
        if (current == null) {
            base = 0.0;
        } else {
            try {
                base = Double.parseDouble(current);
            } catch (NumberFormatException e) {
                throw new CommandException(NOT_A_FLOAT);
            }
        }
        double result = base + delta;
        store.put(key, Double.toString(result));
        return result;
    }

    /** APPEND. Returns the length of the value after appending. */
    public synchronized int appendTo(String key, String suffix) {
        requireNotHash(key);
        String current = get(key);
        String updated = (current == null ? "" : current) + suffix;
        store.put(key, updated);
        return updated.length();
    }

    /** GETSET. Returns the previous value, keeping any TTL the key already had. */
    public synchronized String getSet(String key, String value) {
        requireNotHash(key);
        String previous = get(key);
        store.put(key, value);
        return previous;
    }

    /** SETNX. Returns true if the value was stored. */
    public synchronized boolean setIfAbsent(String key, String value) {
        requireNotHash(key);
        if (get(key) != null) {
            return false;
        }
        store.put(key, value);
        return true;
    }

    /**
     * SET with its conditions, TTL handling and write applied as one step, so a
     * concurrent SET cannot slip between the NX/XX check and the store.
     *
     * @param expireSeconds relative TTL, or null to leave expiry alone
     * @return true if the value was stored
     */
    public synchronized boolean set(String key, String value, boolean nx, boolean xx,
            boolean keepTtl, Long deadlineMillis) {

        // NX/XX ask whether the KEY exists, whatever type it holds, and the answer has to
        // be read before anything is dropped. Removing the hash first makes every hash key
        // look absent, so NX would overwrite it and XX would destroy it on the way to
        // reporting that it did nothing.
        boolean exists = get(key) != null || hashStore.containsKey(key);
        if (nx && exists) {
            return false;
        }
        if (xx && !exists) {
            return false;
        }

        hashStore.remove(key); // committed to writing: the old value goes, whatever it was

        Long retained = keepTtl ? expiryMap.get(key) : null;
        store.put(key, value);
        if (retained != null) {
            expiryMap.put(key, retained);
        } else if (!keepTtl) {
            expiryMap.remove(key);
        }

        if (deadlineMillis != null) {
            // An absolute deadline, so PX 500 keeps its 500ms instead of being truncated
            // to zero seconds and taking the key with it.
            expireAt(key, deadlineMillis);
        }
        return true;
    }

    /** MSET. All pairs land together, so no client observes a half-applied MSET. */
    public synchronized void mset(List<String> flatKeyValues) {
        for (int i = 0; i < flatKeyValues.size(); i += 2) {
            setAndRemoveOlder(flatKeyValues.get(i), flatKeyValues.get(i + 1));
        }
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}
