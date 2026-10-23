package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class HSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (args.size() < 4 || args.size() % 2 != 0) {
            writer.writeError(out, "wrong number of arguments for 'hset' command");
            return;
        }

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, Database.WRONG_TYPE_CODE, Database.WRONG_TYPE);
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
