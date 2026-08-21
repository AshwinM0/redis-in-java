package redis.commands;

import java.io.*;
import java.util.List;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

public class PingCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (args.size() > 2) {
            writer.writeError(out, "wrong number of arguments for 'ping' command");
            return;
        }

        // The argument form replies with a bulk string, as Redis does. A bulk string
        // carries a byte count, so an argument containing CRLF is returned as the data it
        // is instead of ending the reply early. Bare PING keeps its simple-string +PONG.
        if (args.size() == 2)
            writer.writeBulk(out, args.get(1).str);
        else
            writer.writeSimple(out, "PONG");
    }
}