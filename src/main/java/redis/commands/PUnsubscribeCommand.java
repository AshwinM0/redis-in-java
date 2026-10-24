package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PUnsubscribeCommand implements Command {

    private final PubSubManager pubsub;

    public PUnsubscribeCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (args.size() == 1) {
            // Every pattern, one reply each, as Redis sends them. Channel subscriptions are
            // left alone and still count.
            List<String> patterns = pubsub.patternsOf(out);
            if (patterns.isEmpty()) {
                reply(writer, out, null, pubsub.subscriptionCount(out));
            }
            for (String pattern : patterns) {
                reply(writer, out, pattern, pubsub.punsubscribe(out, pattern));
            }
        } else {
            for (int i = 1; i < args.size(); i++) {
                String pattern = args.get(i).str;
                reply(writer, out, pattern, pubsub.punsubscribe(out, pattern));
            }
        }
        out.flush();
    }

    /** ["punsubscribe", pattern, count]; a null pattern when there was nothing to end. */
    private static void reply(RespWriter writer, OutputStream out, String pattern, int remaining) throws IOException {
        writer.writeArrayHeader(out, 3);
        writer.writeBulk(out, "punsubscribe");
        writer.writeBulk(out, pattern);
        writer.writeInt(out, remaining);
    }
}
