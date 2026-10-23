package redis.commands;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class DecrByCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "DECRBY", args.size(), 3))
            return;

        String key = args.get(1).str;
        String decrementStr = args.get(2).str;

        Long decrement = parseLongArg(writer, out, decrementStr);
        if (decrement == null)
            return;

        try {
            writer.writeInt(out, db.incrBy(key, -decrement));
        } catch (CommandException e) {
            writer.writeError(out, e.code(), e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
