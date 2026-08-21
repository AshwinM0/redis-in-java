package redis.resp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-exact RESP reader.
 *
 * <p>Reads directly from an {@link InputStream} so that {@code $N} is honoured as a
 * <em>byte</em> count. Callers should pass a buffered stream; this class reads a byte at a
 * time while scanning header lines.
 */
public class RespParser {

    /**
     * Reads one RESP value.
     *
     * @return the value, or {@code null} on a clean EOF at a message boundary
     * @throws EOFException      if the stream ends part-way through a message
     * @throws RespParseException if the bytes are not valid RESP
     */
    public static Value readValue(InputStream in) throws IOException, RespParseException {
        return readValue(in, 0);
    }

    private static Value readValue(InputStream in, int depth) throws IOException, RespParseException {
        if (depth > Protocol.MAX_NESTING_DEPTH) {
            throw new RespParseException("nesting depth exceeds " + Protocol.MAX_NESTING_DEPTH);
        }

        int prefix = in.read();
        if (prefix == -1) {
            return null; // clean EOF between messages: the client hung up
        }

        switch (prefix) {
            case '*': { // Array
                long count = readLong(in, "multibulk length");
                if (count == -1) {
                    return new Value("null", null); // null array
                }
                if (count < 0 || count > Protocol.MAX_ARRAY_ELEMENTS) {
                    throw new RespParseException("invalid multibulk length: " + count);
                }
                // Reserve only a bounded amount. The count is a claim the client has not
                // backed with any data yet; the list grows as elements actually arrive.
                List<Value> elements = new ArrayList<>(
                        (int) Math.min(count, Protocol.MAX_PREALLOCATED_ELEMENTS));
                for (int i = 0; i < count; i++) {
                    Value element = readValue(in, depth + 1);
                    if (element == null) {
                        throw new EOFException("unexpected EOF inside array");
                    }
                    elements.add(element);
                }
                return new Value("array", elements);
            }

            case '$': { // Bulk string — length is in BYTES
                long length = readLong(in, "bulk length");
                if (length == -1) {
                    return new Value("null", null); // null bulk
                }
                if (length < 0 || length > Protocol.MAX_BULK_LENGTH) {
                    throw new RespParseException("invalid bulk length: " + length);
                }
                // readNBytes keeps reading until it has exactly `length` bytes, so a
                // payload split across TCP segments is reassembled correctly.
                byte[] payload = in.readNBytes((int) length);
                if (payload.length != length) {
                    throw new EOFException("unexpected EOF inside bulk string");
                }
                expectCrlf(in);
                return new Value("bulk", Protocol.fromWire(payload));
            }

            case '+': // Simple string
                return new Value("string", readLine(in));

            case ':': // Integer
                return new Value("integer", readLine(in));

            case '-': // Error
                return new Value("error", readLine(in));

            default:
                throw new RespParseException("Unknown RESP prefix: " + (char) prefix);
        }
    }

    /**
     * Reads bytes up to the next LF and returns them without the trailing CRLF.
     *
     * <p>Gives up past {@link Protocol#MAX_LINE_LENGTH}. A client that opens a header and
     * never terminates it is otherwise an open-ended request for server memory.
     */
    private static String readLine(InputStream in) throws IOException, RespParseException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
        int b;
        while ((b = in.read()) != -1) {
            if (buf.size() >= Protocol.MAX_LINE_LENGTH) {
                throw new RespParseException(
                        "line exceeds " + Protocol.MAX_LINE_LENGTH + " bytes without a terminator");
            }
            if (b == '\n') {
                byte[] line = buf.toByteArray();
                int length = (line.length > 0 && line[line.length - 1] == '\r')
                        ? line.length - 1
                        : line.length;
                return Protocol.fromWire(line, 0, length);
            }
            buf.write(b);
        }
        throw new EOFException("unexpected EOF while reading line");
    }

    private static long readLong(InputStream in, String what) throws IOException, RespParseException {
        String line = readLine(in);
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            throw new RespParseException("invalid " + what + ": '" + line + "'");
        }
    }

    private static void expectCrlf(InputStream in) throws IOException, RespParseException {
        int cr = in.read();
        int lf = in.read();
        if (cr == -1 || lf == -1) {
            throw new EOFException("unexpected EOF after bulk string");
        }
        if (cr != '\r' || lf != '\n') {
            throw new RespParseException("expected CRLF after bulk string");
        }
    }
}
