package redis.core;

/**
 * A condition the client should see as an error reply.
 *
 * <p>Lets {@link Database} make a decision and reject it from inside the same lock that
 * performs the write, so read-modify-write commands stay atomic. The message is the exact
 * text the caller passes to the RESP error writer.
 */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }
}
