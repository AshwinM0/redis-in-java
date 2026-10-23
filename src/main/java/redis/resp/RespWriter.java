package redis.resp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Byte-exact RESP writer.
 *
 * <p>Every bulk-string header is derived from {@code payload.length} after encoding, so the
 * declared length always matches the bytes that follow. Callers should pass a buffered
 * stream and flush once per reply.
 */
public class RespWriter {

    private static final byte[] CRLF = { '\r', '\n' };
    private static final byte[] NULL_BULK = { '$', '-', '1', '\r', '\n' };

    public void writeSimple(OutputStream out, String s) throws IOException {
        out.write('+');
        out.write(Protocol.toWire(singleLine(s)));
        out.write(CRLF);
    }

    public void writeError(OutputStream out, String s) throws IOException {
        writeError(out, "ERR", s);
    }

    /**
     * An error reply under its own code: {@code -WRONGTYPE ...}, {@code -NOAUTH ...}.
     *
     * <p>The first word of an error is its code, and that word is what clients branch on:
     * they map {@code WRONGTYPE}, {@code NOAUTH} or {@code WRONGPASS} to their own error
     * types and treat {@code ERR} as generic. Sending one of those under {@code ERR} hides
     * it, so a client checking for {@code WRONGTYPE} would never see it.
     */
    public void writeError(OutputStream out, String code, String message) throws IOException {
        out.write('-');
        out.write(Protocol.toWire(singleLine(code + " " + message)));
        out.write(CRLF);
    }

    /**
     * Replaces CR and LF with spaces, as Redis does when building error replies.
     *
     * <p>Simple strings and errors are terminated by the first CRLF, so a payload
     * containing one ends the frame early and everything after it is read by the client as
     * a further reply. Several of these payloads are built from client input -- an unknown
     * command name, a rejected command name, a PING argument -- which would let a client
     * forge replies into its own stream and leave it permanently one reply ahead. On a
     * pooled connection those forged replies are read by whoever borrows it next.
     *
     * <p>Bulk strings deliberately do not go through this: they carry an explicit byte
     * count, so CRLF inside them is data and stripping it would break binary safety.
     *
     * <p>Returns the argument itself when there is nothing to replace, so the common
     * replies ({@code +OK}, {@code +PONG}) cost no allocation.
     */
    private static String singleLine(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                return s.replace('\r', ' ').replace('\n', ' ');
            }
        }
        return s;
    }

    public void writeBulk(OutputStream out, String s) throws IOException {
        if (s == null) {
            out.write(NULL_BULK);
            return;
        }
        byte[] payload = Protocol.toWire(s);
        out.write('$');
        out.write(Protocol.toWire(Integer.toString(payload.length)));
        out.write(CRLF);
        out.write(payload);
        out.write(CRLF);
    }

    public void writeInt(OutputStream out, long n) throws IOException {
        out.write(':');
        out.write(Protocol.toWire(Long.toString(n)));
        out.write(CRLF);
    }

    public void writeArrayHeader(OutputStream out, int n) throws IOException {
        out.write('*');
        out.write(Protocol.toWire(Integer.toString(n)));
        out.write(CRLF);
    }

    /**
     * Serialize a parsed request (RESP array) back into RESP. Used by the AOF, which
     * stores commands in exactly the form a client would have sent them.
     */
    public void writeRequest(OutputStream out, Value requestArray) throws IOException {
        List<Value> args = requestArray.array;
        writeArrayHeader(out, args.size());
        for (Value v : args) {
            writeBulk(out, v.str == null ? "" : v.str);
        }
    }
}
