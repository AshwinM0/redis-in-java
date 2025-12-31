package redis.commands;

import java.io.*;
import java.util.*;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

public class SetnxCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {
        if (!arity(writer, out, "SETNX", args.size(), 3))
            return;

        String snxKey = args.get(1).str;
        String snxValue = args.get(2).str;

        if (db.containsHashKey(snxKey)) {
            writer.writeError(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
            return;
        }

        if (db.get(snxKey) != null) {
            writer.writeInt(out, 0);
            return;
        }

        db.set(snxKey, snxValue);
        writer.writeInt(out, 1);
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
