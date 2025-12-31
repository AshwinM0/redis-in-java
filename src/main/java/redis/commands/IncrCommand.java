package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class IncrCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "INCR", args.size(), 2))
            return;

        String key = args.get(1).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String oldVal = db.get(key);
        long num;

        try {
            num = (oldVal == null) ? 0 : Long.parseLong(oldVal);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not an integer or out of range");
            return;
        }

        num++;
        db.set(key, Long.toString(num));

        writer.writeInt(out, num);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
