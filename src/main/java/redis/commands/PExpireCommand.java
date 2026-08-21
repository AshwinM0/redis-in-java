package redis.commands;

import static redis.utils.CommandUtils.arity;
import static redis.utils.CommandUtils.parseLongArg;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** PEXPIRE: a relative TTL in milliseconds. */
public class PExpireCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "PEXPIRE", args.size(), 3))
            return;

        Long millis = parseLongArg(writer, out, args.get(2).str);
        if (millis == null)
            return;

        writer.writeInt(out, db.pexpire(args.get(1).str, millis) ? 1 : 0);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }

    @Override
    public List<Value> aofForm(Database db, List<Value> args) {
        return ExpireCommand.absoluteExpiryForm(db, args.get(1).str);
    }
}
