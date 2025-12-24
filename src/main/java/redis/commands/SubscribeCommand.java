package redis.commands;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.*;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class SubscribeCommand implements Command {

    private final PubSubManager pubsub;

    public SubscribeCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {
        if (!minArity(writer, out, "SUBSCRIBE", args.size(), 2))
            return;

        // Subscribe to each channel
        for (int i = 1; i < args.size(); i++) {
            String channel = args.get(i).str;
            int subCount = pubsub.subscribe(out, channel);

            // Send confirmation: ["subscribe", channel, count]
            writer.writeArrayHeader(out, 3);
            writer.writeBulk(out, "subscribe");
            writer.writeBulk(out, channel);
            writer.writeInt(out, subCount);
        }
        out.flush();
    }
}