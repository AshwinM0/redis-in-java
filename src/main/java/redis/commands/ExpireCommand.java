package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.*;

public class ExpireCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "EXPIRE", args.size(), 3))
            return;

        Long seconds = parseLongArg(writer, out, args.get(2).str);
        if (seconds == null)
            return;

        writer.writeInt(out, db.expire(args.get(1).str, seconds) ? 1 : 0);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }

    @Override
    public List<Value> aofForm(Database db, List<Value> args) {
        return absoluteExpiryForm(db, args.get(1).str);
    }

    /**
     * Rewrites a relative-TTL command into the absolute deadline it actually produced.
     *
     * <p>Shared by EXPIRE and PEXPIRE. Reading the deadline back from the database rather
     * than recomputing it means the log records what happened, not what was asked for —
     * which also covers the cases where the command did something other than set a TTL:
     * a non-positive TTL deletes the key, and a TTL on a missing key does nothing at all.
     */
    static List<Value> absoluteExpiryForm(Database db, String key) {
        Long deadline = db.getExpiry(key);
        if (deadline != null) {
            return List.of(
                    new Value("bulk", "PEXPIREAT"),
                    new Value("bulk", key),
                    new Value("bulk", Long.toString(deadline)));
        }
        if (db.keyExists(key)) {
            return null; // key survived without an expiry: nothing to record
        }
        // The key is gone — either it was never there, or a past deadline deleted it.
        // DEL replays that outcome and is harmless if the key was already absent.
        return List.of(new Value("bulk", "DEL"), new Value("bulk", key));
    }
}
