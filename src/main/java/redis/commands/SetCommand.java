package redis.commands;

import static redis.utils.CommandUtils.*;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class SetCommand implements Command {

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {

        if (!minArity(writer, out, "SET", args.size(), 3)) {
            return;
        }

        String key = args.get(1).str;
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

        if (!db.set(key, value, nx, xx, keepTTL, expireSeconds)) {
            writer.writeBulk(out, null); // NX on an existing key, or XX on a missing one
            return;
        }

        writer.writeSimple(out, "OK");
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }
}
