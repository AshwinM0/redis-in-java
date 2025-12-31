package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class AppendCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "APPEND", args.size(), 3))
            return;

        String key = args.get(1).str;
        String appendVal = args.get(2).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String current = db.get(key);
        if (current == null)
            current = "";

        String newValue = current + appendVal;
        db.set(key, newValue);

        writer.writeInt(out, newValue.length());
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }

}
