package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class HGetAllCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "HGETALL", args.size(), 2)) return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        if (!db.containsHashKey(key)) {
            writer.writeArrayHeader(out, 0);
            return;
        }

        List<Map.Entry<String, String>> entries = db.getAllHashEntries(key);

        writer.writeArrayHeader(out, entries.size() * 2);
        for (var entry : entries) {
            writer.writeBulk(out, entry.getKey());
            writer.writeBulk(out, entry.getValue());
        }
    }
}
