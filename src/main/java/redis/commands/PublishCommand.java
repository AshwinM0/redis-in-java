package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.*;

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
        if (!arity(writer, out, "PUBLISH", args.size(), 3)) return;
        
        String channel = args.get(1).str;
        String message = args.get(2).str;
        
        int receivers = pubsub.publish(channel, message);
        writer.writeInt(out, receivers);
    }
}