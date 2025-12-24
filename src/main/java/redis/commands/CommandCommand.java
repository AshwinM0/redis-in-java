package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class CommandCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args)
            throws IOException {

        // Redis: COMMAND with no args → return empty list or full command meta
        if (args.size() != 1) {
            writer.writeError(out, "wrong number of arguments for 'command' command");
            return;
        }

        writer.writeArrayHeader(out, 0);
    }
}
