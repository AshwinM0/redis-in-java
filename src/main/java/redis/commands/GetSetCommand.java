package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class GetSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "GETSET", args.size(), 3))
            return;

        String key = args.get(1).str;
        String newValue = args.get(2).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String oldValue = db.get(key);

        Long oldTTL = db.getExpiry(key);

        db.set(key, newValue);

        if (oldTTL != null) {
            db.setExpiry(key, oldTTL);
        }

        writer.writeBulk(out, oldValue);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
