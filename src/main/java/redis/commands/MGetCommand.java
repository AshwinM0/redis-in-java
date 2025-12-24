package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class MGetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "MGET", args.size(), 2)) return;

        int count = args.size() - 1;
        writer.writeArrayHeader(out, count);

        for (int i = 1; i < args.size(); i++) {
            String key = args.get(i).str;

            // Hash keys are not MGET-able
            if (db.containsHashKey(key)) {
                writer.writeBulk(out, null);
                continue;
            }

            String val = db.get(key);
            writer.writeBulk(out, val);
        }
    }
}
