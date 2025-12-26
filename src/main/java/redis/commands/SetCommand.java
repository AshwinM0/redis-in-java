package redis.commands;

import static redis.utils.CommandUtils.*;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class SetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, BufferedWriter out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "SET", args.size(), 3)) {
            return;
        }

        String key = args.get(1).str;
        if (db.containsHashKey(key)) {
            db.deleteHashKey(key);
        }
        String value = args.get(2).str;
        boolean keepTTL = false;
        boolean nx = false, xx = false;
        Integer expireSeconds = null;
        boolean hasExpireOption = false;
        boolean abort = false;

        for (int i = 3; i < args.size(); i++) {
            String token = args.get(i).str.toUpperCase();
            switch (token) {
                case "EX":
                case "PX":
                case "EXAT":
                case "PXAT": {
                    if (hasExpireOption) {
                        writer.writeError(out, "syntax error");
                        abort = true;
                        break;
                    }

                    if (i + 1 >= args.size()) {
                        writer.writeError(out, "wrong number of arguments for 'set' command");
                        abort = true;
                        break;
                    }
                    String num = args.get(++i).str;

                    if (token.equals("EX")) {
                        Integer sec = parseIntArg(writer, out, num);
                        if (sec == null) {
                            abort = true;
                            break;
                        }
                        expireSeconds = sec;
                    } else if (token.equals("PX")) {
                        Long ms = parseLongArg(writer, out, num);
                        if (ms == null) {
                            abort = true;
                            break;
                        }
                        expireSeconds = (int) (ms / 1000L);
                    } else if (token.equals("EXAT")) {
                        Long unixSec = parseLongArg(writer, out, num);
                        if (unixSec == null) {
                            abort = true;
                            break;
                        }
                        expireSeconds = (int) (unixSec - (db.getClock().nowMillis() / 1000L));
                    } else { // PXAT
                        Long unixMs = parseLongArg(writer, out, num);
                        if (unixMs == null) {
                            abort = true;
                            break;
                        }
                        expireSeconds = (int) ((unixMs - db.getClock().nowMillis()) / 1000L);
                    }
                    hasExpireOption = true;
                    break;
                }
                case "NX":
                    nx = true;
                    break;
                case "XX":
                    xx = true;
                    break;
                case "KEEPTTL":
                    keepTTL = true;
                    break;
                default:
                    writer.writeError(out, "syntax error");
                    abort = true;
                    break;
            }
            if (abort)
                break;
        }

        if (abort)
            return;

        if (nx && xx) {
            writer.writeError(out, "NX and XX options at the same time are not compatible");
            return;
        }

        if (keepTTL && hasExpireOption) {
            writer.writeError(out, "KEEPTTL and EX/PX/EXAT/PXAT options at the same time are not compatible");
            return;
        }

        if (nx && db.get(key) != null) {
            writer.writeBulk(out, null);
            return;
        }
        if (xx && db.get(key) == null) {
            writer.writeBulk(out, null);
            return;
        }

        if (keepTTL) {
            Long expiry = db.getExpiry(key);
            db.set(key, value);
            if (expiry != null)
                db.setExpiry(key, expiry);
        } else {
            db.setAndRemoveOlder(key, value);
        }

        if (expireSeconds != null && expireSeconds <= 0) {
            db.del(key);
            writer.writeSimple(out, "OK");
            return;
        }

        if (expireSeconds != null) {
            db.expire(key, expireSeconds);
        }

        writer.writeSimple(out, "OK");
    }
}
