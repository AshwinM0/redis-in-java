package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class HLenCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "HLEN", args.size(), 2)) return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        int len = db.hlen(key);
        writer.writeInt(out, len);
    }
}
