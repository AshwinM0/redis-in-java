package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class UnsubscribeCommand implements Command {

    private final PubSubManager pubsub;

    public UnsubscribeCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (args.size() == 1) {
            // Every channel, one reply each, as Redis sends them. Pattern subscriptions are
            // left alone and still count, so the last reply's count is 0 only without any.
            List<String> channels = pubsub.channelsOf(out);
            if (channels.isEmpty()) {
                reply(writer, out, null, pubsub.subscriptionCount(out));
            }
            for (String channel : channels) {
                reply(writer, out, channel, pubsub.unsubscribe(out, channel));
            }
        } else {
            // Unsubscribe from specific channels
            for (int i = 1; i < args.size(); i++) {
                String channel = args.get(i).str;
                reply(writer, out, channel, pubsub.unsubscribe(out, channel));
            }
        }
        out.flush();
    }

    /** ["unsubscribe", channel, count]; a null channel when there was nothing to end. */
    private static void reply(RespWriter writer, OutputStream out, String channel, int remaining) throws IOException {
        writer.writeArrayHeader(out, 3);
        writer.writeBulk(out, "unsubscribe");
        writer.writeBulk(out, channel);
        writer.writeInt(out, remaining);
    }
}
