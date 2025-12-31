package redis.commands;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

public interface Command {
    void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException;

    /**
     * Used by AOF to decide whether to log this command.
     */
    default boolean isWriteCommand() {
        return false;
    }
}