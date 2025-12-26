package redis.core;

import redis.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import redis.time.SystemClock;

public class Database {
    private ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();

    private final Clock clock;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> hashStore = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    public Database() {
        this(new SystemClock());
    }

    public Database(Clock clock) {
        this.clock = clock;
        // Background thread to clean expired keys every 1 seconds
        cleaner.scheduleAtFixedRate(this::removeSampledKeysIfExpired, 5, 1, TimeUnit.SECONDS);
    }

    private void removeSampledKeysIfExpired() {
        long now = clock.nowMillis();
        List<Map.Entry<String, Long>> entriesToCheck = getRandomExpiryEntries(20);
        int expiredCount = 0;
        for (Map.Entry<String, Long> e : entriesToCheck) {
            if (e.getValue() <= now) {
                expiredCount++;
                String key = e.getKey();
                removeExpiredKey(key);
            }
        }

        if (expiredCount / (double) entriesToCheck.size() > 0.25) {
            removeSampledKeysIfExpired();
        }
    }

    private void removeExpiredKey(String key) {
        switch (getKeyType(key)) {
            case "string":
                store.remove(key);
                break;
            case "hash":
                hashStore.remove(key);
                break;
            default:
                break;
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

    private boolean isExpired(String key) {
        Long exp = expiryMap.get(key);
        if (exp == null)
            return false;
        if (clock.nowMillis() > exp) {
            expiryMap.remove(key);
            store.remove(key);
            hashStore.remove(key);
            return true;
        }
        return false;
    }

    private boolean matchesGlob(String text, String pattern) {
        // Convert Redis glob into a regex
        String regex = pattern.replace("*", ".*");
        return text.matches(regex);
    }

    public synchronized String getKeyType(String key) {
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
        if (isExpired(key)) {
            store.remove(key);
            expiryMap.remove(key);
            return null;
        }
        return store.get(key);
    }

    public synchronized int del(String key) {
        boolean removed = false;
        if (store.remove(key) != null)
            removed = true;

        if (hashStore.remove(key) != null)
            removed = true;

        expiryMap.remove(key);
        return removed ? 1 : 0;
    }

    public synchronized boolean stringStoreContainsKey(String key) {
        return store.containsKey(key);
    }

    public synchronized boolean expire(String key, int seconds) {
        long expiryTime = clock.nowMillis() + (seconds * 1000L);

        boolean exists = store.containsKey(key) || hashStore.containsKey(key);
        if (!exists)
            return false;

        if (seconds <= 0) {
            del(key);
            return true;
        }

        expiryMap.put(key, expiryTime);
        return true;
    }

    public synchronized boolean keyExists(String key) {
        // cleanup expired keys first
        ttl(key);

        return store.containsKey(key) || hashStore.containsKey(key);
    }

    public synchronized long ttl(String key) {
        boolean keyExists = store.containsKey(key) || hashStore.containsKey(key); // Add future stores here: setStore,

        if (!keyExists) {
            return -2;
        }

        Long expiryTime = expiryMap.get(key);
        if (expiryTime == null) {
            return -1; // No expiry
        }

        long remainingMillis = expiryMap.get(key) - clock.nowMillis();

        if (remainingMillis <= 0) {
            // Expired → cleanup and return -2
            expiryMap.remove(key);
            store.remove(key);
            hashStore.remove(key);
            return -2;
        }

        return remainingMillis / 1000;
    }

    public synchronized int hset(String hashKey, String field, String value) {
        hashStore.putIfAbsent(hashKey, new ConcurrentHashMap<>());
        String present = hashStore.get(hashKey).put(field, value);

        return present == null ? 1 : 0;
    }

    public synchronized int hsetnx(String key, String field, String value) {
        Map<String, String> map = hashStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        if (map.containsKey(field))
            return 0;

        map.put(field, value);
        return 1;
    }

    public synchronized int hexists(String key, String field) {
        Map<String, String> map = hashStore.get(key);
        return (map != null && map.containsKey(field)) ? 1 : 0;
    }

    public synchronized int hlen(String key) {
        Map<String, String> map = hashStore.get(key);
        return (map == null) ? 0 : map.size();
    }

    public synchronized boolean containsHashKey(String hashKey) {
        return hashStore.containsKey(hashKey);
    }

    public synchronized String hashget(String hashKey, String field) {
        if (isExpired(hashKey)) {
            del(hashKey);
            return null;
        }
        Map<String, String> map = hashStore.get(hashKey);

        if (map == null)
            return null;
        return map.get(field);
    }

    public synchronized List<Map.Entry<String, String>> getAllHashEntries(String hashKey) {
        if (isExpired(hashKey)) {
            del(hashKey);
            return List.of(); // empty list if expired
        }

        Map<String, String> map = hashStore.get(hashKey);
        if (map == null) {
            return List.of(); // empty list if not found
        }

        return new ArrayList<>(map.entrySet());
    }

    public synchronized int deleteHashField(String hashKey, String field) {
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
        if (!hashStore.containsKey(hashKey))
            return 0;
        hashStore.remove(hashKey);
        return 1;
    }

    public synchronized boolean ifHashKeyTypeMismatch(String hashKey) {
        String type = getKeyType(hashKey);
        return type != null && !"hash".equals(type);
    }

    public Long getExpiry(String key) {
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

    public void shutdown() {
        cleaner.shutdownNow();
    }
}
