package redis.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RespWriterTest {

    private RespWriter writer;
    private StringWriter stringWriter;
    private BufferedWriter out;

    @BeforeEach
    void setUp() {
        writer = new RespWriter();
        stringWriter = new StringWriter();
        out = new BufferedWriter(stringWriter);
    }

    private String getOutput() throws IOException {
        out.flush();
        return stringWriter.toString();
    }

    @Test
    void testWriteSimple() throws IOException {
        writer.writeSimple(out, "OK");
        assertEquals("+OK\r\n", getOutput());
    }

    @Test
    void testWriteError() throws IOException {
        writer.writeError(out, "Error");
        assertEquals("-ERR Error\r\n", getOutput());
    }

    @Test
    void testWriteInt() throws IOException {
        writer.writeInt(out, 123);
        assertEquals(":123\r\n", getOutput());
    }

    @Test
    void testWriteBulk() throws IOException {
        writer.writeBulk(out, "hello");
        assertEquals("$5\r\nhello\r\n", getOutput());
    }

    @Test
    void testWriteBulkNull() throws IOException {
        writer.writeBulk(out, null);
        assertEquals("$-1\r\n", getOutput());
    }

    @Test
    void testWriteBulkEmpty() throws IOException {
        writer.writeBulk(out, "");
        assertEquals("$0\r\n\r\n", getOutput());
    }

    @Test
    void testWriteArrayHeader() throws IOException {
        writer.writeArrayHeader(out, 5);
        assertEquals("*5\r\n", getOutput());
    }
}
