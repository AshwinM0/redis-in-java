package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class HGetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "HGET", args.size(), 3)) return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String field = args.get(2).str;
        String value = db.hashget(key, field);

        writer.writeBulk(out, value);
    }
}
