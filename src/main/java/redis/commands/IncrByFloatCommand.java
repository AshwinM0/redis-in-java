package redis.commands;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.*;

public class IncrByFloatCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "INCRBYFLOAT", args.size(), 3))
            return;

        String key = args.get(1).str;
        String incrementStr = args.get(2).str;

        try {
            writer.writeBulk(out, db.incrByFloat(key, incrementStr));
        } catch (CommandException e) {
            writer.writeError(out, e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
