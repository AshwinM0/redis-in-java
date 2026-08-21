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
        out.write(Protocol.toWire(s));
        out.write(CRLF);
    }

    public void writeError(OutputStream out, String s) throws IOException {
        out.write('-');
        out.write(Protocol.toWire("ERR " + s));
        out.write(CRLF);
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
