package redis.commands;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class GetRangeCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!arity(writer, out, "GETRANGE", args.size(), 4)) return;

        String key = args.get(1).str;

        if (db.containsHashKey(key)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        Integer start = parseIntArg(writer, out, args.get(2).str);
        if (start == null) return;

        Integer end = parseIntArg(writer, out, args.get(3).str);
        if (end == null) return;

        String value = db.get(key);
        if (value == null) {
            writer.writeBulk(out, "");
            return;
        }

        int len = value.length();

        // Convert negative indexes
        if (start < 0) start = len + start;
        if (end < 0) end = len + end;

        // Clamp to bounds
        if (start < 0) start = 0;
        if (end >= len) end = len - 1;

        if (start > end || start >= len) {
            writer.writeBulk(out, "");
            return;
        }

        String result = value.substring(start, end + 1);
        writer.writeBulk(out, result);
    }
}
