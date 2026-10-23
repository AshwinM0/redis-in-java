package redis.commands;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class DecrCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "DECR", args.size(), 2))
            return;

        String key = args.get(1).str;

        try {
            writer.writeInt(out, db.incrBy(key, -1));
        } catch (CommandException e) {
            writer.writeError(out, e.code(), e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
