package redis.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import org.junit.jupiter.api.Test;

public class RespParserTest {

    private Value parse(String input) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        return RespParser.readValue(reader);
    }

    @Test
    void testSimpleString() throws IOException {
        Value val = parse("+OK\r\n");
        assertEquals("string", val.typ);
        assertEquals("OK", val.str);
    }

    @Test
    void testError() throws IOException {
        Value val = parse("-Error message\r\n");
        assertEquals("error", val.typ);
        assertEquals("Error message", val.str);
    }

    @Test
    void testInteger() throws IOException {
        Value val = parse(":1000\r\n");
        assertEquals("integer", val.typ);
        assertEquals("1000", val.str);
    }

    @Test
    void testBulkString() throws IOException {
        Value val = parse("$5\r\nhello\r\n");
        assertEquals("bulk", val.typ);
        assertEquals("hello", val.str);
    }

    @Test
    void testEmptyBulkString() throws IOException {
        Value val = parse("$0\r\n\r\n");
        assertEquals("bulk", val.typ);
        assertEquals("", val.str);
    }

    @Test
    void testNullBulkString() throws IOException {
        Value val = parse("$-1\r\n");
        assertEquals("null", val.typ);
        assertNull(val.str);
    }

    @Test
    void testArray() throws IOException {
        Value val = parse("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
        assertEquals("array", val.typ);
        assertEquals(2, val.array.size());
        assertEquals("foo", val.array.get(0).str);
        assertEquals("bar", val.array.get(1).str);
    }

    @Test
    void testNestedArray() throws IOException {
        // *2\r\n *1\r\n $3\r\nfoo\r\n $3\r\nbar\r\n
        Value val = parse("*2\r\n*1\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
        assertEquals("array", val.typ);
        assertEquals(2, val.array.size());

        Value nested = val.array.get(0);
        assertEquals("array", nested.typ);
        assertEquals(1, nested.array.size());
        assertEquals("foo", nested.array.get(0).str);

        assertEquals("bar", val.array.get(1).str);
    }

    @Test
    void testEmptyArray() throws IOException {
        Value val = parse("*0\r\n");
        assertEquals("array", val.typ);
        assertEquals(0, val.array.size());
    }

    @Test
    void testUnknownPrefix() {
        assertThrows(IOException.class, () -> parse("?invalid\r\n"));
    }

    @Test
    void testEOF() throws IOException {
        Value val = parse("");
        assertNull(val);
    }
}
