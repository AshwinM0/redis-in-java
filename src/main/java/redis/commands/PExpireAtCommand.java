package redis.commands;

import static redis.utils.CommandUtils.arity;
import static redis.utils.CommandUtils.parseLongArg;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Sets an absolute expiry deadline, in epoch milliseconds.
 *
 * <p>This is the form the AOF stores for every expiry, because it replays to the same
 * moment in time regardless of when the replay happens.
 */
public class PExpireAtCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "PEXPIREAT", args.size(), 3))
            return;

        Long deadline = parseLongArg(writer, out, args.get(2).str);
        if (deadline == null)
            return;

        writer.writeInt(out, db.expireAt(args.get(1).str, deadline) ? 1 : 0);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
