package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class GetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "GET", args.size(), 2)) return;

        String key = args.get(1).str;

        // Every other string command guards this; GET returning nil made a hash look
        // like a missing key, which is the one answer that invites a client to overwrite it.
        if (db.containsHashKey(key)) {
            writer.writeError(out, Database.WRONG_TYPE_CODE, Database.WRONG_TYPE);
            return;
        }

        String value = db.get(key);

        writer.writeBulk(out, value);
    }
}
