package dev.nuaforma.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaBaselineTest {
    @Test
    void javaTestsCompileAndExecuteOnJava17() {
        assertEquals(17, Runtime.version().feature());
    }
}
