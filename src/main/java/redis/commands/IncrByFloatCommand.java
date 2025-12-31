package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.*;

public class IncrByFloatCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "INCRBYFLOAT", args.size(), 3))
            return;

        String key = args.get(1).str;
        String incrementStr = args.get(2).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String oldVal = db.get(key);
        Double base;

        if (oldVal == null) {
            base = 0.0;
        } else {
            base = parseDoubleArg(writer, out, oldVal);
            if (base == null)
                return;
        }

        Double incVal = parseDoubleArg(writer, out, incrementStr);
        if (incVal == null)
            return;

        Double result = base + incVal;

        db.set(key, Double.toString(result));
        writer.writeBulk(out, result.toString());
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
