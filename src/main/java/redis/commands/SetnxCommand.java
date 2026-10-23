package redis.commands;

import java.io.*;
import java.util.*;

import redis.core.CommandException;
import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import static redis.utils.CommandUtils.arity;

public class SetnxCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (!arity(writer, out, "SETNX", args.size(), 3))
            return;

        String snxKey = args.get(1).str;
        String snxValue = args.get(2).str;

        try {
            writer.writeInt(out, db.setIfAbsent(snxKey, snxValue) ? 1 : 0);
        } catch (CommandException e) {
            writer.writeError(out, e.code(), e.getMessage());
        }
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
