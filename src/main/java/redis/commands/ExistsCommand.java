package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.minArity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class ExistsCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "EXISTS", args.size(), 2)) return;

        int count = 0;

        for (int i = 1; i < args.size(); i++) {
            String key = args.get(i).str;
            if (db.keyExists(key)) count++;
        }

        writer.writeInt(out, count);
    }
}
