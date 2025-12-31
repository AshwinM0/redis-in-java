package redis.resp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class RespWriter {
    public void writeSimple(BufferedWriter out, String s) throws IOException {
        out.write("+" + s + "\r\n");
    }

    public void writeError(BufferedWriter out, String s) throws IOException {
        out.write("-ERR " + s + "\r\n");
    }

    public void writeBulk(BufferedWriter out, String s) throws IOException {
        if (s == null) {
            out.write("$-1\r\n");
            return;
        }
        out.write("$" + s.length() + "\r\n" + s + "\r\n");
    }

    public void writeInt(BufferedWriter out, long n) throws IOException {
        out.write(":" + n + "\r\n");
    }

    public void writeArrayHeader(BufferedWriter out, int n) throws IOException {
        out.write("*" + n + "\r\n");
    }

    /**
     * Serialize a parsed request (RESP array) back into RESP and write to OutputStream.
     * This is used for AOF.
     */
    public void writeRequest(OutputStream out, Value requestArray) throws IOException {
        // requestArray.typ == "array"
        List<Value> args = requestArray.array;

        out.write(("*" + args.size() + "\r\n").getBytes());

        for (Value v : args) {
            String s = v.str == null ? "" : v.str;
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes());
            out.write(bytes);
            out.write("\r\n".getBytes());
        }
    }
}
