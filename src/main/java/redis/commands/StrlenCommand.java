package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class StrlenCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "STRLEN", args.size(), 2)) return;

        String key = args.get(1).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String val = db.get(key);
        int len = (val == null) ? 0 : val.length();

        writer.writeInt(out, len);
    }
}
