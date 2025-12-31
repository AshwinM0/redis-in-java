package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class HSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (args.size() < 4 || args.size() % 2 != 0) {
            writer.writeError(out, "wrong number of arguments for 'hset' command");
            return;
        }

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        int newFields = 0;

        for (int i = 2; i < args.size(); i += 2) {
            String field = args.get(i).str;
            String value = args.get(i + 1).str;

            newFields += db.hset(key, field, value);
        }

        writer.writeInt(out, newFields);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
