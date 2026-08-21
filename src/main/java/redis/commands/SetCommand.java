package redis.commands;

import static redis.utils.CommandUtils.*;

import redis.core.Database;
import redis.resp.RespWriter;
import redis.resp.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        Long deadlineMillis = null;
        boolean hasExpireOption = false;
        boolean abort = false;

        for (int i = 3; i < args.size(); i++) {
            String token = args.get(i).str.toUpperCase(Locale.ROOT);
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

                    Long n = parseLongArg(writer, out, args.get(++i).str);
                    if (n == null) {
                        abort = true;
                        break;
                    }

                    // Every form resolves to one absolute deadline in milliseconds. The
                    // previous code funnelled all four through an int count of seconds,
                    // which truncated PX 500 to zero (deleting the key outright) and
                    // overflowed on any EXAT more than ~68 years out (deleting it too).
                    try {
                        switch (token) {
                            case "EX":
                                deadlineMillis = Math.addExact(db.getClock().nowMillis(),
                                        Math.multiplyExact(n, 1000L));
                                break;
                            case "PX":
                                deadlineMillis = Math.addExact(db.getClock().nowMillis(), n);
                                break;
                            case "EXAT":
                                deadlineMillis = Math.multiplyExact(n, 1000L);
                                break;
                            default: // PXAT
                                deadlineMillis = n;
                                break;
                        }
                    } catch (ArithmeticException e) {
                        writer.writeError(out, "invalid expire time in 'set' command");
                        abort = true;
                        break;
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

        if (!db.set(key, value, nx, xx, keepTTL, deadlineMillis)) {
            writer.writeBulk(out, null); // NX on an existing key, or XX on a missing one
            return;
        }

        writer.writeSimple(out, "OK");
    }

    @Override
    public boolean isWriteCommand() {
        return true;
    }

    /**
     * Logs what the SET actually did, not what it was asked to do.
     *
     * <p>Three of the option forms are not replay-safe as written. A relative TTL
     * (EX/PX) would be recomputed against the restart clock. NX and XX are decisions made
     * against the live keyspace, and replaying them against a partially rebuilt one can
     * reach the opposite verdict — logging a declined {@code SET k v NX} verbatim would
     * overwrite on replay the very key whose existence caused it to decline. KEEPTTL
     * depends on a TTL that replay may not have restored yet.
     *
     * <p>So the log gets a plain {@code SET key value}, carrying {@code PXAT} when the key
     * ended up with a deadline, and nothing at all when the key is not there.
     *
     * <p>{@code CommandProcessor} holds the database monitor across the execute and this
     * call, so the state read back here is the state this command produced.
     */
    @Override
    public List<Value> aofForm(Database db, List<Value> args) {
        String key = args.get(1).str;

        String stored = db.get(key);
        if (stored == null) {
            return null; // declined by NX/XX, or a past deadline deleted it outright
        }

        List<Value> form = new ArrayList<>(List.of(
                new Value("bulk", "SET"),
                new Value("bulk", key),
                new Value("bulk", stored)));

        Long deadline = db.getExpiry(key);
        if (deadline != null) {
            form.add(new Value("bulk", "PXAT"));
            form.add(new Value("bulk", Long.toString(deadline)));
        }
        return form;
    }
}
