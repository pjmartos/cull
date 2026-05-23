package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class GreeterTest {
    @Test
    void greets() {
        assertEquals("hello, world", new Greeter().greet("world"));
    }
}
