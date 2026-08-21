package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

public interface Command {
    void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException;

    /**
     * Used by AOF to decide whether to log this command.
     */
    default boolean isWriteCommand() {
        return false;
    }

    /**
     * The form of this command to write to the AOF, called after {@link #execute} has run.
     *
     * <p>The AOF is replayed at an unknown later time, so anything it stores has to mean
     * the same thing then as it did now. A command carrying a <em>relative</em> TTL does
     * not: {@code EXPIRE k 60} re-evaluated after a restart grants a fresh sixty seconds
     * to a key that may have died hours ago. Commands in that position override this to
     * emit an absolute deadline instead, reading back the deadline the write actually
     * produced.
     *
     * @return the arguments to log, or {@code null} to log nothing
     */
    default List<Value> aofForm(Database db, List<Value> args) {
        return args;
    }
}