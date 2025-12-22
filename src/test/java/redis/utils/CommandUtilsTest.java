package redis.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.resp.RespWriter;

public class CommandUtilsTest {

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
    void testAritySuccess() throws IOException {
        boolean result = CommandUtils.arity(writer, out, "CMD", 2, 2);
        assertTrue(result);
        assertEquals("", getOutput());
    }

    @Test
    void testArityFailure() throws IOException {
        boolean result = CommandUtils.arity(writer, out, "CMD", 1, 2);
        assertFalse(result);
        assertEquals("-ERR wrong number of arguments for 'cmd' command\r\n", getOutput());
    }

    @Test
    void testMinAritySuccess() throws IOException {
        boolean result = CommandUtils.minArity(writer, out, "CMD", 2, 2);
        assertTrue(result);
        assertEquals("", getOutput());
    }

    @Test
    void testMinAritySuccessMore() throws IOException {
        boolean result = CommandUtils.minArity(writer, out, "CMD", 3, 2);
        assertTrue(result);
        assertEquals("", getOutput());
    }

    @Test
    void testMinArityFailure() throws IOException {
        boolean result = CommandUtils.minArity(writer, out, "CMD", 1, 2);
        assertFalse(result);
        assertEquals("-ERR wrong number of arguments for 'cmd' command\r\n", getOutput());
    }

    @Test
    void testParseIntArgSuccess() throws IOException {
        Integer result = CommandUtils.parseIntArg(writer, out, "123");
        assertEquals(123, result);
        assertEquals("", getOutput());
    }

    @Test
    void testParseIntArgFailure() throws IOException {
        Integer result = CommandUtils.parseIntArg(writer, out, "abc");
        assertNull(result);
        assertEquals("-ERR value is not an integer or out of range\r\n", getOutput());
    }

    @Test
    void testParseLongArgSuccess() throws IOException {
        Long result = CommandUtils.parseLongArg(writer, out, "1234567890");
        assertEquals(1234567890L, result);
        assertEquals("", getOutput());
    }

    @Test
    void testParseLongArgFailure() throws IOException {
        Long result = CommandUtils.parseLongArg(writer, out, "abc");
        assertNull(result);
        assertEquals("-ERR value is not an integer or out of range\r\n", getOutput());
    }

    @Test
    void testParseDoubleArgSuccess() throws IOException {
        Double result = CommandUtils.parseDoubleArg(writer, out, "123.45");
        assertEquals(123.45, result);
        assertEquals("", getOutput());
    }

    @Test
    void testParseDoubleArgFailure() throws IOException {
        Double result = CommandUtils.parseDoubleArg(writer, out, "abc");
        assertNull(result);
        assertEquals("-ERR value is not a valid float\r\n", getOutput());
    }
}