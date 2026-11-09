package org.zeropointlauncher.launch.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentTemplateTest {
    @Test
    void parsesLiteralAndVariablesInOneToken() {
        var template = ArgumentTemplate.parse("-Dprop=${name}:${suffix}");

        assertEquals("-Dprop=${name}:${suffix}", template.rawPattern());
        assertEquals(List.of(
                new ArgumentTemplate.Part.Literal("-Dprop="),
                new ArgumentTemplate.Part.Variable("name"),
                new ArgumentTemplate.Part.Literal(":"),
                new ArgumentTemplate.Part.Variable("suffix")
        ), template.parts());
    }

    @Test
    void rendersWithoutRecursiveExpansionAndKeepsTokenBoundary() {
        var template = ArgumentTemplate.parse("-Dprop=${name}:${suffix}");

        String rendered = template.render(Map.of(
                "name", "C:/Program Files/MC",
                "suffix", "${foo}"
        ));

        assertEquals("-Dprop=C:/Program Files/MC:${foo}", rendered);
    }

    @Test
    void rendersPlainLiteralAndStrayBraces() {
        assertEquals("--demo", ArgumentTemplate.parse("--demo").render(Map.of()));
        assertEquals("a}b$c", ArgumentTemplate.parse("a}b$c").render(Map.of()));
    }

    @Test
    void missingVariableIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ArgumentTemplate.parse("${name}").render(Map.of()));

        assertTrue(error.getMessage().contains("name"));
    }

    @Test
    void emptyAndUnclosedPlaceholdersAreRejected() {
        var empty = assertThrows(IllegalArgumentException.class,
                () -> ArgumentTemplate.parse("${}"));
        assertTrue(empty.getMessage().contains("占位符"));

        var unclosed = assertThrows(IllegalArgumentException.class,
                () -> ArgumentTemplate.parse("${foo"));
        assertTrue(unclosed.getMessage().contains("未闭合"));
    }

    @Test
    void partsAreImmutable() {
        var template = ArgumentTemplate.parse("--demo");

        assertThrows(UnsupportedOperationException.class,
                () -> template.parts().add(new ArgumentTemplate.Part.Literal("x")));
    }

    @Test
    void nullTokenIsRejected() {
        assertThrows(NullPointerException.class, () -> ArgumentTemplate.parse(null));
    }
}
