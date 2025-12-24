package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class TypeCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args)
            throws IOException {

        if (!arity(writer, out, "TYPE", args.size(), 2)) return;

        String key = args.get(1).str;
        String type = db.getKeyType(key);

        if (type == null) type = "none";

        writer.writeSimple(out, type);
    }
}
