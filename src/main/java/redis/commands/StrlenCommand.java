package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class StrlenCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "STRLEN", args.size(), 2)) return;

        String key = args.get(1).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, Database.WRONG_TYPE_CODE, Database.WRONG_TYPE);
            return;
        }

        String val = db.get(key);
        int len = (val == null) ? 0 : val.length();

        writer.writeInt(out, len);
    }
}
