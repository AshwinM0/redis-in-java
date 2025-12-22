package redis.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class ValueTest {

    @Test
    void testStringValue() {
        Value val = new Value("bulk", "hello");
        assertEquals("bulk", val.typ);
        assertEquals("hello", val.str);
        assertNull(val.array);
        assertEquals("hello", val.toString());
    }

    @Test
    void testIntegerValue() {
        Value val = new Value("integer", "123");
        assertEquals("integer", val.typ);
        assertEquals("123", val.str);
        assertEquals("123", val.toString());
    }

    @Test
    void testArrayValue() {
        List<Value> elements = new ArrayList<>();
        elements.add(new Value("bulk", "A"));
        elements.add(new Value("bulk", "B"));

        Value val = new Value("array", elements);
        assertEquals("array", val.typ);
        assertNull(val.str);
        assertEquals(elements, val.array);
        assertEquals("[A, B]", val.toString());
    }

    @Test
    void testNestedArrayValue() {
        List<Value> inner = new ArrayList<>();
        inner.add(new Value("bulk", "leaf"));

        List<Value> outer = new ArrayList<>();
        outer.add(new Value("array", inner));

        Value val = new Value("array", outer);
        assertEquals("[[leaf]]", val.toString());
    }
}
