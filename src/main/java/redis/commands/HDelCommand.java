package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.minArity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class HDelCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "HDEL", args.size(), 3))
            return;

        String key = args.get(1).str;

        if (db.ifHashKeyTypeMismatch(key)) {
            writer.writeError(out, Database.WRONG_TYPE_CODE, Database.WRONG_TYPE);
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
