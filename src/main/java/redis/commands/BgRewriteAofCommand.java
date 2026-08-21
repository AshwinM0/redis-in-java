package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import redis.core.Database;
import redis.persistence.AofManager;
import redis.resp.RespWriter;
import redis.resp.Value;

/**
 * Compacts the append-only file.
 *
 * <p>Named for the Redis command, but performed inline rather than in a background child:
 * this server has no fork, and doing it on the calling thread keeps the guarantee that the
 * new log is in place before the reply is sent.
 */
public class BgRewriteAofCommand implements Command {

    private final AofManager aof; // null when persistence is off

    public BgRewriteAofCommand(AofManager aof) {
        this.aof = aof;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (args.size() != 1) {
            writer.writeError(out, "wrong number of arguments for 'bgrewriteaof' command");
            return;
        }
        if (aof == null) {
            writer.writeError(out, "AOF is not enabled");
            return;
        }

        aof.rewrite(db);
        writer.writeSimple(out, "Background append only file rewriting started");
    }
}
