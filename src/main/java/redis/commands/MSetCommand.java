package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class MSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (args.size() < 3 || args.size() % 2 == 0) {
            writer.writeError(out, "wrong number of arguments for 'mset' command");
            return;
        }

        for (int i = 1; i < args.size(); i += 2) {
            String key = args.get(i).str;
            String value = args.get(i + 1).str;

            // Convert from hash to string if necessary
            db.setAndRemoveOlder(key, value);
        }

        writer.writeSimple(out, "OK");
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
