package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class HSetnxCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "HSETNX", args.size(), 4))
            return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        String field = args.get(2).str;
        String value = args.get(3).str;

        int result = db.hsetnx(key, field, value);
        writer.writeInt(out, result);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
