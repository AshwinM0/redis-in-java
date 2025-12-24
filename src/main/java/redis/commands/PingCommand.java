package redis.commands;

import java.io.*;
import java.util.List;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PingCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {
        if (args.size() > 2) {
            writer.writeError(out, "wrong number of arguments for 'ping' command");
            return;
        }

        if (args.size() == 2)
            writer.writeSimple(out, args.get(1).str);
        else
            writer.writeSimple(out, "PONG");
    }
}