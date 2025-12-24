package redis.commands;

import java.io.BufferedWriter;
import java.io.IOException;
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
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {
        if (args.size() == 1) {
            // Unsubscribe from all
            pubsub.unsubscribeAll(out);
            writer.writeArrayHeader(out, 3);
            writer.writeBulk(out, "unsubscribe");
            writer.writeBulk(out, null); // null channel
            writer.writeInt(out, 0);
        } else {
            // Unsubscribe from specific channels
            for (int i = 1; i < args.size(); i++) {
                String channel = args.get(i).str;
                int remaining = pubsub.unsubscribe(out, channel);

                writer.writeArrayHeader(out, 3);
                writer.writeBulk(out, "unsubscribe");
                writer.writeBulk(out, channel);
                writer.writeInt(out, remaining);
            }
        }
        out.flush();
    }
}