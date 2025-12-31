package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import static redis.utils.CommandUtils.minArity;

public class DelCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "DEL", args.size(), 2))
            return;

        int count = 0;
        for (int i = 1; i < args.size(); i++) {
            count += db.del(args.get(i).str);
        }

        writer.writeInt(out, count);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
