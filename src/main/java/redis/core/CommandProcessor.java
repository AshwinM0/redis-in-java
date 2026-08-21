package redis.core;

import java.util.*;

import redis.commands.AppendCommand;
import redis.commands.BgRewriteAofCommand;
import redis.commands.Command;
import redis.commands.CommandCommand;
import redis.commands.DecrByCommand;
import redis.commands.DecrCommand;
import redis.commands.DelCommand;
import redis.commands.EchoCommand;
import redis.commands.ExistsCommand;
import redis.commands.ExpireCommand;
import redis.commands.PExpireAtCommand;
import redis.commands.PExpireCommand;
import redis.commands.PTTLCommand;
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
import redis.commands.QuitCommand;
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
import java.util.Locale;

public class CommandProcessor {

    /**
     * Passes bytes straight through while noting whether the reply began with {@code '-'}.
     *
     * <p>A command that answers with an error changed nothing, so logging it puts a line
     * in the AOF that replay can only reject a second time. Commands report failure by
     * writing an error reply and nothing else, so the reply is the signal -- which avoids
     * threading a success flag back through all thirty-odd implementations.
     */
    private static final class ErrorSniffingStream extends OutputStream {

        private final OutputStream delegate;
        private Boolean firstByteWasError;

        ErrorSniffingStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        boolean repliedWithError() {
            return Boolean.TRUE.equals(firstByteWasError);
        }

        private void note(int firstByte) {
            if (firstByteWasError == null) {
                firstByteWasError = firstByte == '-';
            }
        }

        @Override
        public void write(int b) throws IOException {
            note(b);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) {
                note(b[off]);
            }
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

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
        commands.put("PTTL", new PTTLCommand());
        commands.put("PEXPIRE", new PExpireCommand());
        commands.put("PEXPIREAT", new PExpireAtCommand());

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
        commands.put("BGREWRITEAOF", new BgRewriteAofCommand(aof));
        commands.put("ECHO", new EchoCommand());
        commands.put("QUIT", new QuitCommand());

        commands.put("SUBSCRIBE", new SubscribeCommand(pubsub));
        commands.put("UNSUBSCRIBE", new UnsubscribeCommand(pubsub));
        commands.put("PUBLISH", new PublishCommand(pubsub));
        // Add others...
    }

    /** @see redis.commands.Command#writesToOtherConnections() */
    public boolean writesToOtherConnections(String name) {
        Command cmd = commands.get(name);
        return cmd != null && cmd.writesToOtherConnections();
    }

    public void executeCommand(
            String name,
            Database db,
            RespWriter writer,
            OutputStream out,
            List<Value> args,
            boolean fromReplay)
            throws IOException {

        Command cmd = commands.get(name);

        if (cmd == null) {
            writer.writeError(out, "unknown command '" + name.toLowerCase(Locale.ROOT) + "'");
            return;
        }

        boolean logging = !fromReplay && aof != null && cmd.isWriteCommand();

        if (!logging) {
            cmd.execute(db, writer, out, args);
            return;
        }

        // aofForm reads back the state the command produced, so it has to see exactly
        // that state. Every Database method synchronizes on the database, so holding the
        // same monitor across both makes the pair atomic against any concurrent command;
        // without it a write landing in between would be logged in this one's place.
        List<Value> durable;
        ErrorSniffingStream sniffer = new ErrorSniffingStream(out);
        synchronized (db) {
            cmd.execute(db, writer, sniffer, args);
            durable = sniffer.repliedWithError() ? null : cmd.aofForm(db, args);
        }

        // A null form means the command changed nothing worth replaying — it was
        // rejected, or it declined to act: a SET blocked by NX, an EXPIRE against a key
        // that was not there.
        if (durable != null) {
            aof.append(new Value("array", durable));
        }
    }
}
