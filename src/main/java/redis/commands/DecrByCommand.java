package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class DecrByCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "DECRBY", args.size(), 3))
            return;

        String key = args.get(1).str;
        String decrementStr = args.get(2).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        Long decrement = parseLongArg(writer, out, decrementStr);
        if (decrement == null)
            return;

        String currentVal = db.get(key);
        long base;

        try {
            base = (currentVal == null) ? 0 : Long.parseLong(currentVal);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not an integer or out of range");
            return;
        }

        long result = base - decrement;
        db.set(key, Long.toString(result));

        writer.writeInt(out, result);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
