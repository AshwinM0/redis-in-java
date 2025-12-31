package redis.core;

import java.util.*;

import redis.commands.AppendCommand;
import redis.commands.Command;
import redis.commands.CommandCommand;
import redis.commands.DecrByCommand;
import redis.commands.DecrCommand;
import redis.commands.DelCommand;
import redis.commands.ExistsCommand;
import redis.commands.ExpireCommand;
import redis.commands.FlushAllCommand;
import redis.commands.GetCommand;
import redis.commands.GetRangeCommand;
import redis.commands.GetSetCommand;
import redis.commands.HDelCommand;
import redis.commands.HExistsCommand;
import redis.commands.HGetAllCommand;
import redis.commands.HGetCommand;
import redis.commands.HLenCommand;
import redis.commands.HSetCommand;
import redis.commands.HSetnxCommand;
import redis.commands.IncrByCommand;
import redis.commands.IncrByFloatCommand;
import redis.commands.IncrCommand;
import redis.commands.KeysCommand;
import redis.commands.MGetCommand;
import redis.commands.MSetCommand;
import redis.commands.PingCommand;
import redis.commands.PublishCommand;
import redis.commands.SetCommand;
import redis.commands.SetnxCommand;
import redis.commands.StrlenCommand;
import redis.commands.SubscribeCommand;
import redis.commands.TTLCommand;
import redis.commands.TypeCommand;
import redis.commands.UnsubscribeCommand;
import redis.persistence.AofManager;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.*;

public class CommandProcessor {

    private final Map<String, Command> commands = new HashMap<>();
    private final PubSubManager pubsub;
    private final AofManager aof; // can be null

    public CommandProcessor() {
        this(null, null);
    }

    public CommandProcessor(PubSubManager pubsub) {
        this(pubsub, null);
    }

    public CommandProcessor(PubSubManager pubsub, AofManager aof) {
        this.pubsub = pubsub;
        this.aof = aof;
        registerCommands();
    }

    private void registerCommands() {
        commands.put("PING", new PingCommand());
        commands.put("SET", new SetCommand());
        commands.put("SETNX", new SetnxCommand());
        commands.put("GET", new GetCommand());
        commands.put("GETSET", new GetSetCommand());
        commands.put("DEL", new DelCommand());
        commands.put("EXPIRE", new ExpireCommand());
        commands.put("TTL", new TTLCommand());

        commands.put("HSET", new HSetCommand());
        commands.put("HSETNX", new HSetnxCommand());
        commands.put("HGET", new HGetCommand());
        commands.put("HGETALL", new HGetAllCommand());
        commands.put("HDEL", new HDelCommand());
        commands.put("HLEN", new HLenCommand());
        commands.put("HEXISTS", new HExistsCommand());

        commands.put("INCR", new IncrCommand());
        commands.put("DECR", new DecrCommand());
        commands.put("INCRBY", new IncrByCommand());
        commands.put("DECRBY", new DecrByCommand());
        commands.put("INCRBYFLOAT", new IncrByFloatCommand());

        commands.put("APPEND", new AppendCommand());
        commands.put("GETRANGE", new GetRangeCommand());
        commands.put("STRLEN", new StrlenCommand());

        commands.put("MSET", new MSetCommand());
        commands.put("MGET", new MGetCommand());

        commands.put("EXISTS", new ExistsCommand());
        commands.put("KEYS", new KeysCommand());

        commands.put("FLUSHALL", new FlushAllCommand());
        commands.put("TYPE", new TypeCommand());
        commands.put("COMMAND", new CommandCommand());

        commands.put("SUBSCRIBE", new SubscribeCommand(pubsub));
        commands.put("UNSUBSCRIBE", new UnsubscribeCommand(pubsub));
        commands.put("PUBLISH", new PublishCommand(pubsub));
        // Add others...
    }

    public void executeCommand(
            String name,
            Database db,
            RespWriter writer,
            BufferedWriter out,
            List<Value> args,
            boolean fromReplay)
            throws IOException {

        Command cmd = commands.get(name);

        if (cmd == null) {
            writer.writeError(out, "unknown command '" + name.toLowerCase() + "'");
            return;
        }

        cmd.execute(db, writer, out, args);

        // Append to AOF if:
        // - not from replay
        // - AOF configured
        // - command is mutating
        if (!fromReplay && aof != null && cmd.isWriteCommand()) {
            Value req = new Value("array", args);
            aof.append(req);
        }
    }
}
