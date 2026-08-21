package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.arity;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PublishCommand implements Command {

    private final PubSubManager pubsub;

    public PublishCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (!arity(writer, out, "PUBLISH", args.size(), 3))
            return;

        String channel = args.get(1).str;
        String message = args.get(2).str;

        // Fan out first, holding no lock of our own. Only then take this connection's
        // lock, for the reply alone -- see writesToOtherConnections().
        int receivers = pubsub.publish(channel, message);

        synchronized (pubsub.lockFor(out)) {
            writer.writeInt(out, receivers);
            out.flush();
        }
    }

    @Override
    public boolean writesToOtherConnections() {
        return true;
    }
}
