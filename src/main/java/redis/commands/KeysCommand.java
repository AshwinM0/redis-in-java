package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class KeysCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args)
            throws IOException {

        if (!arity(writer, out, "KEYS", args.size(), 2)) return;

        String pattern = args.get(1).str;

        List<String> keys = db.getKeysMatching(pattern);

        writer.writeArrayHeader(out, keys.size());
        for (String k : keys) {
            writer.writeBulk(out, k);
        }
    }
}
