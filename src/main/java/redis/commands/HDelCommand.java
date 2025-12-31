package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.minArity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class HDelCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "HDEL", args.size(), 3))
            return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        int deleted = 0;

        for (int i = 2; i < args.size(); i++) {
            String field = args.get(i).str;
            deleted += db.deleteHashField(key, field);
        }

        writer.writeInt(out, deleted);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
