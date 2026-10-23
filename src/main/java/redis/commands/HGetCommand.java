package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class HGetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "HGET", args.size(), 3)) return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, Database.WRONG_TYPE_CODE, Database.WRONG_TYPE);
            return;
        }

        String field = args.get(2).str;
        String value = db.hashget(key, field);

        writer.writeBulk(out, value);
    }
}
