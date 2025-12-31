package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class FlushAllCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args)
            throws IOException {

        // Redis FLUSHALL takes 1 argument always
        if (args.size() != 1) {
            writer.writeError(out, "wrong number of arguments for 'flushall' command");
            return;
        }

        db.flushAll();
        writer.writeSimple(out, "OK");
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
