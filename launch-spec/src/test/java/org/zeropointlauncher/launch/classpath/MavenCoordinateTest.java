package org.zeropointlauncher.launch.classpath;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MavenCoordinateTest {
    @Test
    void parsesPlainCoordinateAndDerivesArtifactPath() {
        var coordinate = MavenCoordinate.parse("com.google.code.gson:gson:2.10");

        assertEquals("com.google.code.gson", coordinate.groupId());
        assertEquals("gson", coordinate.artifactId());
        assertEquals("2.10", coordinate.version());
        assertEquals(Optional.empty(), coordinate.classifier());
        assertEquals("jar", coordinate.extension());
        assertEquals("com/google/code/gson/gson/2.10/gson-2.10.jar",
                coordinate.toRelativePath());
    }

    @Test
    void parsesClassifierAndExtension() {
        var nativeCoordinate = MavenCoordinate.parse(
                "org.lwjgl:lwjgl:3.3.1:natives-linux@zip");

        assertEquals(Optional.of("natives-linux"), nativeCoordinate.classifier());
        assertEquals("zip", nativeCoordinate.extension());
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.zip",
                nativeCoordinate.toRelativePath());
    }

    @Test
    void trimsWhitespaceAndDefaultsExtension() {
        var coordinate = MavenCoordinate.parse(
                "  net.java.jinput:jinput-platform:2.0.5  ");

        assertEquals("net/java/jinput/jinput-platform/2.0.5/"
                + "jinput-platform-2.0.5.jar", coordinate.toRelativePath());
    }

    @Test
    void dottedGroupIdBecomesDirectorySegments() {
        var coordinate = new MavenCoordinate(
                "a.b.c", "artifact", "1.0", Optional.of("classifier"), "jar");

        assertEquals("a/b/c/artifact/1.0/artifact-1.0-classifier.jar",
                coordinate.toRelativePath());
    }

    @Test
    void rejectsMalformedCoordinates() {
        for (String raw : new String[]{
                "",
                "   ",
                "group:artifact",
                "group:artifact:version:classifier:extra",
                " :artifact:version",
                "group::version",
                "group:artifact: ",
                "group:artifact:version:",
                "group:artifact:version@",
                "group:artifact:version@ ",
                "group:artifact:version@jar@extra",
                "g:../../outside:1",
                ".g:a:1",
                "../g:a:1",
                "g:a:../1",
                "g:a:1:..",
                "g:a:1:a/b",
                "g:a:1@jar:extra",
                "g:a:1@../evil",
                "g:a:1@C:",
                "g:a:1@C:/evil"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> MavenCoordinate.parse(raw), raw);
        }
    }

    @Test
    void constructorRejectsUnsafeFields() {
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "../../g", "a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                ".g", "a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g.", "a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g..a", "a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g/a", "a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g:a", "a", "1", Optional.empty(), "jar"));

        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "../a", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a/b", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a\\b", "1", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "..", Optional.empty(), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "../1", Optional.empty(), "jar"));

        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.of(".."), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.of("a/b"), "jar"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.empty(), "."));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.empty(), ".."));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.empty(), ""));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.empty(), "jar:extra"));
        assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate(
                "g", "a", "1", Optional.empty(), "../jar"));
    }

    @Test
    void nullCoordinateAndNullComponentsAreRejected() {
        assertThrows(NullPointerException.class, () -> MavenCoordinate.parse(null));
        assertThrows(NullPointerException.class, () -> new MavenCoordinate(
                null, "artifact", "1", Optional.empty(), "jar"));
        assertThrows(NullPointerException.class, () -> new MavenCoordinate(
                "group", null, "1", Optional.empty(), "jar"));
        assertThrows(NullPointerException.class, () -> new MavenCoordinate(
                "group", "artifact", null, Optional.empty(), "jar"));
        assertThrows(NullPointerException.class, () -> new MavenCoordinate(
                "group", "artifact", "1", null, "jar"));
        assertThrows(NullPointerException.class, () -> new MavenCoordinate(
                "group", "artifact", "1", Optional.empty(), null));
    }
}
