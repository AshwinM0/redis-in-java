package redis.commands;

import static redis.utils.CommandUtils.arity;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** PTTL: milliseconds remaining, -1 for no expiry, -2 for no key. */
public class PTTLCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "PTTL", args.size(), 2))
            return;

        writer.writeInt(out, db.pttl(args.get(1).str));
    }
}
