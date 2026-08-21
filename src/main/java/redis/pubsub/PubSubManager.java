package redis.pubsub;

import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import redis.resp.RespWriter;

public class PubSubManager {
    // Channel → Set of subscribers (their output writers)
    private final Map<String, Set<Subscriber>> channelSubscribers = new ConcurrentHashMap<>();

    // Track which channels each client is subscribed to
    private final Map<OutputStream, Set<String>> clientChannels = new ConcurrentHashMap<>();

    private final RespWriter writer = new RespWriter();

    /**
     * Subscribe a client to a channel.
     * Returns the total number of channels this client is subscribed to.
     */
    public Integer subscribe(OutputStream out, String channel) {
        Subscriber subscriber = new Subscriber(out);

        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        clientChannels.computeIfAbsent(out, k -> ConcurrentHashMap.newKeySet()).add(channel);

        return clientChannels.get(out).size();
    }

    /**
     * Check if a client is currently subscribed to any channels.
     */
    public boolean isSubscribed(OutputStream out) {
        Set<String> channels = clientChannels.get(out);
        return channels != null && !channels.isEmpty();
    }

    /**
     * Unsubscribe a client from a channel.
     * Returns the remaining number of channels this client is subscribed to.
     */
    public Integer unsubscribe(OutputStream out, String channel) {
        Subscriber subscriber = new Subscriber(out);

        // Remove from channel's subscriber set
        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        if (subscribers != null) {
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) {
                channelSubscribers.remove(channel);
            }
        }

        // Update client's subscription tracking
        Set<String> channels = clientChannels.get(out);
        if (channels != null) {
            channels.remove(channel);
            if (channels.isEmpty()) {
                clientChannels.remove(out);
                return 0;
            }
            return channels.size();
        }
        return 0;
    }

    /**
     * Unsubscribe a client from ALL channels.
     */
    public void unsubscribeAll(OutputStream out) {
        Set<String> channels = clientChannels.remove(out);
        if (channels != null) {
            Subscriber subscriber = new Subscriber(out);
            for (String channel : channels) {
                Set<Subscriber> subs = channelSubscribers.get(channel);
                if (subs != null) {
                    subs.remove(subscriber);
                    if (subs.isEmpty()) {
                        channelSubscribers.remove(channel);
                    }
                }
            }
        }
    }

    /**
     * Publish a message to a channel.
     * Returns the number of clients that received the message.
     */
    public int publish(String channel, String message) {
        Set<Subscriber> subscribers = channelSubscribers.get(channel);
        if (subscribers == null || subscribers.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        List<Subscriber> toRemove = new ArrayList<>();

        for (Subscriber sub : subscribers) {
            try {
                synchronized (sub.lock) {
                    // Redis Pub/Sub message format: ["message", channel, data]
                    writer.writeArrayHeader(sub.out, 3);
                    writer.writeBulk(sub.out, "message");
                    writer.writeBulk(sub.out, channel);
                    writer.writeBulk(sub.out, message);
                    sub.out.flush();
                }
                delivered++;
            } catch (IOException e) {
                // Client disconnected, mark for removal
                toRemove.add(sub);
            }
        }

        // Clean up dead connections
        for (Subscriber dead : toRemove) {
            subscribers.remove(dead);
            clientChannels.remove(dead.out);
        }

        return delivered;
    }
}
