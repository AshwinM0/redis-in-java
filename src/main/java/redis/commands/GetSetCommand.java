package redis.commands;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static redis.utils.CommandUtils.arity;

public class GetSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!arity(writer, out, "GETSET", args.size(), 3))
            return;

        String key = args.get(1).str;
        String newValue = args.get(2).str;

        try {
            writer.writeBulk(out, db.getSet(key, newValue));
        } catch (CommandException e) {
            writer.writeError(out, e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
