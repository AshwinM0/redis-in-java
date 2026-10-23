package redis.commands;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class IncrByCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "INCRBY", args.size(), 3))
            return;

        String key = args.get(1).str;
        String incrementStr = args.get(2).str;

        Long increment = parseLongArg(writer, out, incrementStr);
        if (increment == null)
            return;

        try {
            writer.writeInt(out, db.incrBy(key, increment));
        } catch (CommandException e) {
            writer.writeError(out, e.code(), e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
