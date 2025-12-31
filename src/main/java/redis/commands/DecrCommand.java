package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class DecrCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "DECR", args.size(), 2))
            return;

        String key = args.get(1).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String val = db.get(key);
        long num;

        try {
            num = (val == null) ? 0 : Long.parseLong(val);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not an integer or out of range");
            return;
        }

        num -= 1;
        db.set(key, Long.toString(num));

        writer.writeInt(out, num);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
