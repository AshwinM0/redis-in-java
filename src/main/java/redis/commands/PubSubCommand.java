package redis.commands;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

import static redis.utils.CommandUtils.minArity;

import redis.core.Database;
import redis.pubsub.PubSubManager;
import redis.resp.RespWriter;
import redis.resp.Value;

/**
 * PUBSUB CHANNELS [pattern], PUBSUB NUMSUB [channel ...], PUBSUB NUMPAT.
 *
 * <p>NUMSUB is how {@code @socket.io/redis-adapter} counts the servers sharing a namespace:
 * each one subscribes to the namespace's request channel.
 */
public class PubSubCommand implements Command {

    private final PubSubManager pubsub;

    public PubSubCommand(PubSubManager pubsub) {
        this.pubsub = pubsub;
    }

    @Override
    public void execute(Database db, RespWriter writer, OutputStream out, List<Value> args) throws IOException {
        if (!minArity(writer, out, "PUBSUB", args.size(), 2))
            return;

        String subcommand = args.get(1).str.toUpperCase(Locale.ROOT);
        switch (subcommand) {
            case "CHANNELS": {
                if (args.size() > 3) {
                    wrongArity(writer, out, "channels");
                    return;
                }
                String pattern = (args.size() == 3) ? args.get(2).str : null;
                List<String> channels = pubsub.activeChannels(pattern);
                writer.writeArrayHeader(out, channels.size());
                for (String channel : channels) {
                    writer.writeBulk(out, channel);
                }
                break;
            }
            case "NUMSUB": {
                // A flat array of pairs: channel, count, channel, count...
                writer.writeArrayHeader(out, (args.size() - 2) * 2);
                for (int i = 2; i < args.size(); i++) {
                    String channel = args.get(i).str;
                    writer.writeBulk(out, channel);
                    writer.writeInt(out, pubsub.numSub(channel));
                }
                break;
            }
            case "NUMPAT": {
                if (args.size() != 2) {
                    wrongArity(writer, out, "numpat");
                    return;
                }
                writer.writeInt(out, pubsub.numPat());
                break;
            }
            default:
                writer.writeError(out, "unknown subcommand '" + args.get(1).str + "'. Try PUBSUB HELP.");
        }
    }

    private static void wrongArity(RespWriter writer, OutputStream out, String subcommand) throws IOException {
        writer.writeError(out, "wrong number of arguments for 'pubsub|" + subcommand + "' command");
    }
}
