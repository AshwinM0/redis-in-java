package redis.core;

/**
 * A condition the client should see as an error reply.
 *
 * <p>Lets {@link Database} make a decision and reject it from inside the same lock that
 * performs the write, so read-modify-write commands stay atomic. The code and message are
 * exactly what the caller passes to the RESP error writer.
 */
public class CommandException extends RuntimeException {

    private final String code;

    public CommandException(String message) {
        this("ERR", message);
    }

    /**
     * @param code the error code the reply goes out under, such as {@code WRONGTYPE}. The
     *             command catching this cannot tell one condition from another, so the
     *             code has to travel with the message.
     */
    public CommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The reply's error code: {@code ERR} unless the condition has one of its own. */
    public String code() {
        return code;
    }
}
