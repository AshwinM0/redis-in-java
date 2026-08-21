package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MSetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (args.size() < 3 || args.size() % 2 == 0) {
            writer.writeError(out, "wrong number of arguments for 'mset' command");
            return;
        }

        List<String> flat = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            flat.add(args.get(i).str);
        }
        db.mset(flat);

        writer.writeSimple(out, "OK");
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
