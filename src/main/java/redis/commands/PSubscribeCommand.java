package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.*;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PSubscribeCommand implements Command {

    private final PubSubManager pubsub;

    public PSubscribeCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (!minArity(writer, out, "PSUBSCRIBE", args.size(), 2))
            return;

        for (int i = 1; i < args.size(); i++) {
            String pattern = args.get(i).str;
            int subCount = pubsub.psubscribe(out, pattern);

            // Send confirmation: ["psubscribe", pattern, count]
            writer.writeArrayHeader(out, 3);
            writer.writeBulk(out, "psubscribe");
            writer.writeBulk(out, pattern);
            writer.writeInt(out, subCount);
        }
        out.flush();
    }
}
