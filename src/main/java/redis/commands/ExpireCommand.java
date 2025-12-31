package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.*;

public class ExpireCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "EXPIRE", args.size(), 3))
            return;

        String key = args.get(1).str;
        String secStr = args.get(2).str;

        Integer seconds = parseIntArg(writer, out, secStr);
        if (seconds == null)
            return;

        boolean success = db.expire(key, seconds);

        writer.writeInt(out, success ? 1 : 0);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
