package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Acknowledges the client's intent to disconnect. {@code ClientHandler} closes the
 * connection once this reply has been flushed.
 */
public class QuitCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        writer.writeSimple(out, "OK");
    }
}
