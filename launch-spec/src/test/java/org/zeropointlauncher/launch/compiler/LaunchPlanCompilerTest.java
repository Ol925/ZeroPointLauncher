package org.zeropointlauncher.launch.compiler;

import org.junit.jupiter.api.Test;
import org.zeropointlauncher.launch.classpath.ClientJarRef;
import org.zeropointlauncher.launch.model.LaunchArgument;
import org.zeropointlauncher.launch.model.Library;
import org.zeropointlauncher.launch.model.ManifestFragment;
import org.zeropointlauncher.launch.model.RuleContext;
import org.zeropointlauncher.launch.parse.ManifestFragmentParser;
import org.zeropointlauncher.launch.resolve.NativeExtractRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LaunchPlanCompilerTest {
    private static final String WINDOWS_LIBRARIES = "C:/mc/libraries";
    private static final String WINDOWS_VERSIONS = "C:/mc/versions";
    private static final String LINUX_LIBRARIES = "/mc/libraries";
    private static final String LINUX_VERSIONS = "/mc/versions";

    private final ManifestFragmentParser parser = new ManifestFragmentParser();

    @Test
    void compilesLegacyManifestWithJvmDefaultsAndNativeRequests() throws Exception {
        var manifest = fixture("versions/1.12.2-trimmed.json");
        var target = context("windows", "x86_64");

        var plan = LaunchPlanCompiler.compile(
                manifest, target, client("1.12.2"), legacyPublicValues());

        assertEquals("1.12.2", plan.manifestId());
        assertEquals("net.minecraft.client.main.Main", plan.mainClass());
        assertEquals(List.of(
                "-cp",
                "${classpath}",
                "-Djava.library.path=${natives_directory}"
        ), rawPatterns(plan.jvmTemplates()));
        assertEquals(List.of(
                "--username", "${auth_player_name}",
                "--version", "${version_name}",
                "--gameDir", "${game_directory}",
                "--assetsDir", "${assets_root}",
                "--assetIndex", "${assets_index_name}",
                "--uuid", "${auth_uuid}",
                "--accessToken", "${auth_access_token}",
                "--userType", "${user_type}",
                "--versionType", "${version_type}"
        ), rawPatterns(plan.gameTemplates()));

        assertEquals(2, plan.legacyNativeRequests().size());
        assertTrue(plan.legacyNativeRequests().stream()
                .map(NativeExtractRequest::classifier)
                .allMatch("natives-windows"::equals));
        assertEquals("1.12.2/1.12.2.jar",
                plan.classpathPlan().entries().getLast());
        assertTrue(plan.requiredVariables().containsAll(List.of(
                "classpath", "natives_directory", "game_directory",
                "assets_root", "auth_access_token")));
        assertFalse(plan.requiredVariables().contains("auth_player_name"));
        assertFalse(plan.requiredVariables().contains("user_type"));

        var librariesRoot = Path.of(WINDOWS_LIBRARIES);
        var versionsRoot = Path.of(WINDOWS_VERSIONS);
        var boundValues = legacyBoundValues("C:/mc/natives");
        var command = plan.renderCommandLine(
                "java", boundValues, librariesRoot, versionsRoot, ";");

        String expectedClasspath = String.join(";",
                librariesRoot.resolve(
                        "net/sf/jopt-simple/jopt-simple/5.0.3/jopt-simple-5.0.3.jar")
                        .toString(),
                librariesRoot.resolve(
                        "org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/"
                                + "lwjgl-platform-2.9.4-nightly-20150209.jar")
                        .toString(),
                versionsRoot.resolve("1.12.2/1.12.2.jar").toString());

        assertEquals("java", command.get(0));
        assertEquals("-cp", command.get(1));
        assertEquals(expectedClasspath, command.get(2));
        assertEquals("-Djava.library.path=C:/mc/natives", command.get(3));
        assertEquals("net.minecraft.client.main.Main", command.get(4));
        assertEquals("--username", command.get(5));
        assertEquals("Demo Player", command.get(6));
        assertEquals("--accessToken",
                command.get(command.indexOf("--accessToken")));
        assertEquals("secret-token",
                command.get(command.indexOf("--accessToken") + 1));

        var sanitized = plan.renderSanitizedCommandLine(
                "java", boundValues, librariesRoot, versionsRoot, ";");
        assertFalse(sanitized.contains("secret-token"));
        assertTrue(sanitized.contains("***"));
        assertEquals("Demo Player",
                sanitized.get(sanitized.indexOf("--username") + 1));
    }

    @Test
    void compilesModernLinuxManifestWithOneClasspathEntry() throws Exception {
        var manifest = fixture("versions/1.20.1-trimmed.json");
        var target = context("linux", "x86_64");

        var plan = LaunchPlanCompiler.compile(
                manifest, target, client("1.20.1"), modernPublicValues());

        assertEquals(1, classpathVariableCount(plan.jvmTemplates()));
        var jvmPatterns = rawPatterns(plan.jvmTemplates());
        int classpathFlag = jvmPatterns.indexOf("-cp");
        assertTrue(classpathFlag >= 0);
        assertEquals("${classpath}", jvmPatterns.get(classpathFlag + 1));

        assertEquals(List.of(
                "com/google/code/gson/gson/2.10/gson-2.10.jar",
                "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar",
                "io/netty/netty-transport-native-epoll/4.1.82.Final/"
                        + "netty-transport-native-epoll-4.1.82.Final-linux-x86_64.jar",
                "1.20.1/1.20.1.jar"
        ), plan.classpathPlan().entries());

        assertTrue(plan.requiredVariables().containsAll(List.of(
                "classpath", "natives_directory", "game_directory", "assets_root",
                "auth_access_token", "auth_xuid", "clientid")));
        assertFalse(plan.requiredVariables().contains("auth_player_name"));
        assertFalse(plan.requiredVariables().contains("resolution_width"));
        assertFalse(plan.requiredVariables().contains("resolution_height"));

        var librariesRoot = Path.of(LINUX_LIBRARIES);
        var versionsRoot = Path.of(LINUX_VERSIONS);
        var command = plan.renderCommandLine(
                "java",
                modernBoundValues("/mc/game", "/mc/assets", "/mc/natives"),
                librariesRoot, versionsRoot, ":");

        String expectedClasspath = String.join(":",
                librariesRoot.resolve(
                        "com/google/code/gson/gson/2.10/gson-2.10.jar").toString(),
                librariesRoot.resolve(
                        "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar").toString(),
                librariesRoot.resolve(
                        "io/netty/netty-transport-native-epoll/4.1.82.Final/"
                                + "netty-transport-native-epoll-4.1.82.Final-linux-x86_64.jar")
                        .toString(),
                versionsRoot.resolve("1.20.1/1.20.1.jar").toString());

        assertEquals(expectedClasspath, command.get(command.indexOf("-cp") + 1));
        assertTrue(command.contains("-Djava.library.path=/mc/natives"));
        assertEquals("Demo Player",
                command.get(command.indexOf("--username") + 1));
        assertEquals("secret-token",
                command.get(command.indexOf("--accessToken") + 1));
    }

    @Test
    void compilesModernWindowsManifestWithPlatformClasspath() throws Exception {
        var manifest = fixture("versions/1.20.1-trimmed.json");
        var target = context("windows", "x86_64");

        var plan = LaunchPlanCompiler.compile(
                manifest, target, client("1.20.1"), modernPublicValues());

        assertEquals(1, classpathVariableCount(plan.jvmTemplates()));
        assertEquals(List.of(
                "com/google/code/gson/gson/2.10/gson-2.10.jar",
                "1.20.1/1.20.1.jar"
        ), plan.classpathPlan().entries());
        assertTrue(rawPatterns(plan.jvmTemplates()).stream()
                .anyMatch(pattern -> pattern.startsWith("-XX:HeapDumpPath=")));

        var librariesRoot = Path.of(WINDOWS_LIBRARIES);
        var versionsRoot = Path.of(WINDOWS_VERSIONS);
        var command = plan.renderCommandLine(
                "java",
                modernBoundValues("C:/mc/game", "C:/mc/assets", "C:/mc/natives"),
                librariesRoot, versionsRoot, ";");

        String expectedClasspath = String.join(";",
                librariesRoot.resolve(
                        "com/google/code/gson/gson/2.10/gson-2.10.jar").toString(),
                versionsRoot.resolve("1.20.1/1.20.1.jar").toString());
        assertEquals(expectedClasspath, command.get(command.indexOf("-cp") + 1));
    }

    @Test
    void disabledCustomResolutionDoesNotRequireResolutionValues() throws Exception {
        var manifest = fixture("versions/1.20.1-trimmed.json");
        var target = context("windows", "x86_64",
                Map.of("has_custom_resolution", false));

        var plan = LaunchPlanCompiler.compile(
                manifest, target, client("1.20.1"), modernPublicValues());

        assertFalse(plan.requiredVariables().contains("resolution_width"));
        assertFalse(plan.requiredVariables().contains("resolution_height"));
        assertFalse(rawPatterns(plan.gameTemplates()).contains("${resolution_width}"));
        assertFalse(rawPatterns(plan.gameTemplates()).contains("${resolution_height}"));
    }

    @Test
    void enabledCustomResolutionRequiresAndBakesPublicValues() throws Exception {
        var manifest = fixture("versions/1.20.1-trimmed.json");
        var target = context("windows", "x86_64",
                Map.of("has_custom_resolution", true));

        var missing = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, target, client("1.20.1"), modernPublicValues()));
        assertTrue(missing.getMessage().contains("resolution_width"));

        var publicValues = new HashMap<>(modernPublicValues());
        publicValues.put("resolution_width", "1280");
        publicValues.put("resolution_height", "720");
        var plan = LaunchPlanCompiler.compile(
                manifest, target, client("1.20.1"), publicValues);

        var command = plan.renderCommandLine(
                "java",
                modernBoundValues("C:/mc/game", "C:/mc/assets", "C:/mc/natives"),
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");

        assertEquals("1280", command.get(command.indexOf("--width") + 1));
        assertEquals("720", command.get(command.indexOf("--height") + 1));
    }

    @Test
    void modernClasspathEntryMustBeUniqueAndUsePlaceholder() {
        assertCompileSucceeds("-cp", "${classpath}");
        assertCompileSucceeds("-classpath", "${classpath}");
        assertCompileSucceeds("--class-path", "${classpath}");
        assertCompileSucceeds("--class-path=${classpath}");

        assertCompileFails("Classpath", "-Dfoo=bar");
        assertCompileFails("重复", "-cp", "${classpath}", "-cp", "${classpath}");
        assertCompileFails("重复", "-cp", "${classpath}",
                "--class-path=${classpath}");
        assertCompileFails("硬编码", "-cp", "lib.jar");
        assertCompileFails("硬编码", "--class-path=lib.jar");
        assertCompileFails("不支持", "-cp=${classpath}");
        assertCompileFails("位置非法", "${classpath}");
    }

    @Test
    void legacyQuotedArgumentsStaySingleTokens() {
        var manifest = new ManifestFragment(
                "legacy-quoted",
                null,
                "example.Main",
                "--name \"Demo Player\" --path 'C:\\Program Files\\MC' --flag",
                null,
                List.of());

        var plan = LaunchPlanCompiler.compile(
                manifest, context("windows", "x86_64"),
                client("legacy-quoted"), Map.of());

        assertEquals(List.of(
                "--name", "Demo Player",
                "--path", "C:\\Program Files\\MC",
                "--flag"
        ), rawPatterns(plan.gameTemplates()));
        assertEquals(List.of("-cp", "${classpath}"), rawPatterns(plan.jvmTemplates()));
        assertTrue(plan.legacyNativeRequests().isEmpty());

        var command = plan.renderCommandLine(
                "java", Map.of(),
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");

        assertEquals(command.indexOf("--name") + 1, command.indexOf("Demo Player"));
        assertEquals(command.indexOf("--path") + 1,
                command.indexOf("C:\\Program Files\\MC"));
        assertTrue(command.contains("--flag"));
    }

    @Test
    void modernGameArgumentsWithoutJvmRequireClasspath() {
        var manifest = new ManifestFragment(
                "modern-game-only",
                null,
                "example.Main",
                null,
                new ManifestFragment.ArgumentsBlock(
                        List.of(new LaunchArgument.Direct("--demo")),
                        List.of()),
                List.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, context("windows", "x86_64"),
                        client("modern-game-only"), Map.of()));

        assertTrue(error.getMessage().contains("Classpath"));
    }

    @Test
    void modernArgumentsStructureWinsOverLegacyString() {
        var manifest = new ManifestFragment(
                "hybrid",
                null,
                "example.Main",
                "--legacy",
                new ManifestFragment.ArgumentsBlock(List.of(), List.of()),
                List.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, context("windows", "x86_64"),
                        client("hybrid"), Map.of()));

        assertTrue(error.getMessage().contains("Classpath"));
    }

    @Test
    void legacyExplicitEmptyQuotedTokenIsPreserved() {
        var manifest = new ManifestFragment(
                "empty-token",
                null,
                "example.Main",
                "--flag \"\" --other",
                null,
                List.of());
        var plan = LaunchPlanCompiler.compile(
                manifest, context("windows", "x86_64"),
                client("empty-token"), Map.of());

        assertEquals(List.of("--flag", "", "--other"),
                rawPatterns(plan.gameTemplates()));

        var command = plan.renderCommandLine(
                "java", Map.of(),
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");
        int flagIndex = command.indexOf("--flag");
        assertEquals("", command.get(flagIndex + 1));
        assertEquals("--other", command.get(flagIndex + 2));
    }

    @Test
    void missingPublicValueNamesTheVariable() throws Exception {
        var publicValues = new HashMap<>(legacyPublicValues());
        publicValues.remove("auth_player_name");

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        fixture("versions/1.12.2-trimmed.json"),
                        context("windows", "x86_64"),
                        client("1.12.2"),
                        publicValues));

        assertTrue(error.getMessage().contains("auth_player_name"));
    }

    @Test
    void unknownTemplateVariableIsRejected() {
        var manifest = new ManifestFragment(
                "unknown-variable",
                null,
                "example.Main",
                "--value ${unknown}",
                null,
                List.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, context("windows", "x86_64"),
                        client("unknown-variable"), Map.of()));

        assertTrue(error.getMessage().contains("unknown"));
    }

    @Test
    void publicValuesCannotCarryExecutionOnlyVariables() {
        var manifest = new ManifestFragment(
                "execution-variable",
                null,
                "example.Main",
                "--gameDir ${game_directory}",
                null,
                List.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, context("windows", "x86_64"),
                        client("execution-variable"),
                        Map.of("game_directory", "/game")));

        assertTrue(error.getMessage().contains("执行阶段"));
    }

    @Test
    void nativeSelectionIssuesAbortCompilation() {
        var nativeLibrary = new Library(
                "example:arm-native:1",
                null,
                null,
                Map.of("windows", "natives-windows-${arch}"),
                List.of(),
                null);
        var manifest = new ManifestFragment(
                "arm-native",
                null,
                "example.Main",
                "--flag",
                null,
                List.of(nativeLibrary));

        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        manifest, context("windows", "arm64"),
                        client("arm-native"), Map.of()));

        assertTrue(error.getMessage().contains("example:arm-native:1"));
        assertTrue(error.getMessage().contains("arm64"));
    }

    @Test
    void sanitizedCommandLineMasksSessionVariables() {
        var manifest = new ManifestFragment(
                "session-sanitized",
                null,
                "example.Main",
                "--session=${auth_session} --session prefix-${auth_session} "
                        + "--accessToken ${auth_access_token}",
                null,
                List.of());
        var plan = LaunchPlanCompiler.compile(
                manifest, context("windows", "x86_64"),
                client("session-sanitized"), Map.of());
        var boundValues = Map.of(
                "auth_session", "session-secret",
                "auth_access_token", "token-secret");

        var real = plan.renderCommandLine(
                "java", boundValues,
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");
        var sanitized = plan.renderSanitizedCommandLine(
                "java", boundValues,
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");

        assertTrue(real.contains("--session=session-secret"));
        assertTrue(real.contains("prefix-session-secret"));
        assertTrue(real.contains("token-secret"));
        assertFalse(sanitized.contains("--session=session-secret"));
        assertFalse(sanitized.contains("prefix-session-secret"));
        assertFalse(sanitized.contains("token-secret"));
        assertTrue(sanitized.contains("--session=***"));
        assertTrue(sanitized.contains("prefix-***"));
        assertEquals("***", sanitized.get(sanitized.indexOf("--accessToken") + 1));
    }

    @Test
    void finalCommandLineRejectsDynamicExtraClasspathFlag() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}", "${launcher_name}"),
                context("windows", "x86_64"),
                client("dynamic-cp"),
                Map.of("launcher_name", "-cp"));

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));

        assertTrue(error.getMessage().contains("Classpath"));
    }

    @Test
    void finalCommandLineRejectsClasspathSystemProperty() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}", "${launcher_name}"),
                context("windows", "x86_64"),
                client("dynamic-classpath-prop"),
                Map.of("launcher_name", "-Djava.class.path=hack.jar"));

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));

        assertTrue(error.getMessage().contains("java.class.path"));
        assertTrue(error.getMessage().contains("索引"));
        assertFalse(error.getMessage().contains("hack.jar"));
    }

    @Test
    void finalCommandLineRejectsJarOption() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}", "-jar"),
                context("windows", "x86_64"),
                client("jar-option"),
                Map.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));

        assertTrue(error.getMessage().contains("-jar"));
        assertTrue(error.getMessage().contains("索引"));
    }

    @Test
    void finalCommandLineRejectsBareClasspathSystemProperty() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}", "-Djava.class.path"),
                context("windows", "x86_64"),
                client("bare-classpath-prop"),
                Map.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));

        assertTrue(error.getMessage().contains("java.class.path"));
        assertTrue(error.getMessage().contains("索引"));
    }

    @Test
    void finalCommandLineAllowsNonClasspathPrefixedOptions() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}", "-cpu", "-cprofile"),
                context("windows", "x86_64"),
                client("non-classpath-prefix"),
                Map.of());

        var command = plan.renderCommandLine(
                "java", Map.of(),
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");

        assertTrue(command.contains("-cpu"));
        assertTrue(command.contains("-cprofile"));
    }

    @Test
    void classpathOverrideExceptionDoesNotLeakSessionSecret() {
        var plan = LaunchPlanCompiler.compile(
                modernJvm("-cp", "${classpath}",
                        "-Djava.class.path=${auth_session}"),
                context("windows", "x86_64"),
                client("secret-diagnostic"),
                Map.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java",
                        Map.of("auth_session", "super-secret-session"),
                        Path.of(WINDOWS_LIBRARIES),
                        Path.of(WINDOWS_VERSIONS),
                        ";"));

        assertFalse(error.getMessage().contains("super-secret-session"));
        assertTrue(error.getMessage().contains("java.class.path"));
        assertTrue(error.getMessage().contains("索引"));
    }

    @Test
    void sanitizedCommandLineMasksOnlyAccessToken() {
        var manifest = new ManifestFragment(
                "sanitized",
                null,
                "example.Main",
                "--accessToken ${auth_access_token} --name ${auth_player_name}",
                null,
                List.of());
        var plan = LaunchPlanCompiler.compile(
                manifest, context("windows", "x86_64"),
                client("sanitized"),
                Map.of("auth_player_name", "Demo Player"));
        var boundValues = Map.of("auth_access_token", "secret-token");

        var real = plan.renderCommandLine(
                "java", boundValues,
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");
        var sanitized = plan.renderSanitizedCommandLine(
                "java", boundValues,
                Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";");

        assertEquals(real.size(), sanitized.size());
        int tokenIndex = real.indexOf("--accessToken");
        assertEquals("secret-token", real.get(tokenIndex + 1));
        assertEquals("***", sanitized.get(tokenIndex + 1));
        for (int i = 0; i < real.size(); i++) {
            if (i != tokenIndex + 1) {
                assertEquals(real.get(i), sanitized.get(i), "index " + i);
            }
        }
    }

    @Test
    void physicalRootContainingTargetClasspathSeparatorIsRejected() {
        var plan = compileSimpleLegacyPlan("windows", "x86_64");
        var badRoot = Path.of("mc;libraries");

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        badRoot, Path.of("versions"), ";"));

        assertTrue(error.getMessage().contains("分隔符"));
    }

    @Test
    void linuxColonInPhysicalPathIsRejected() {
        var plan = compileSimpleLegacyPlan("linux", "x86_64");

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "/opt/java:bad", Map.of(),
                        Path.of("libraries"), Path.of("versions"), ":"));

        assertTrue(error.getMessage().contains("分隔符"));
    }

    @Test
    void extraWindowsColonInJavaExecutableIsRejected() {
        var plan = compileSimpleLegacyPlan("windows", "x86_64");

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "C:\\libs:bad\\java.exe", Map.of(),
                        Path.of("libraries"), Path.of("versions"), ";"));

        assertTrue(error.getMessage().contains("冒号"));
    }

    @Test
    void pathSeparatorMustMatchTargetOs() {
        var windowsPlan = compileSimpleLegacyPlan("windows", "x86_64");
        for (String separator : List.of(":", ",")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> windowsPlan.renderCommandLine(
                            "java", Map.of(),
                            Path.of(WINDOWS_LIBRARIES),
                            Path.of(WINDOWS_VERSIONS),
                            separator),
                    separator);
            assertTrue(error.getMessage().contains("pathSeparator"),
                    error.getMessage());
        }

        var linuxPlan = compileSimpleLegacyPlan("linux", "x86_64");
        var error = assertThrows(IllegalArgumentException.class,
                () -> linuxPlan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(LINUX_LIBRARIES),
                        Path.of(LINUX_VERSIONS),
                        ";"));
        assertTrue(error.getMessage().contains("pathSeparator"));
    }

    @Test
    void classpathEntryEndingWithWildcardIsRejected() {
        var wildcardLibrary = new Library(
                "example:wildcard:1",
                null,
                new Library.Downloads(
                        new Library.DownloadArtifact("libs/*", "sha1", 1L, null),
                        Map.of()),
                null,
                List.of(),
                null);
        var manifest = new ManifestFragment(
                "wildcard",
                null,
                "example.Main",
                "--flag",
                null,
                List.of(wildcardLibrary));
        var plan = LaunchPlanCompiler.compile(
                manifest, context("windows", "x86_64"),
                client("wildcard"), Map.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));
        assertTrue(error.getMessage().contains("*"));

        var clientPlan = LaunchPlanCompiler.compile(
                new ManifestFragment(
                        "wildcard-client",
                        null,
                        "example.Main",
                        "--flag",
                        null,
                        List.of()),
                context("windows", "x86_64"),
                new ClientJarRef("wildcard/*"),
                Map.of());

        var clientError = assertThrows(IllegalArgumentException.class,
                () -> clientPlan.renderCommandLine(
                        "java", Map.of(),
                        Path.of(WINDOWS_LIBRARIES), Path.of(WINDOWS_VERSIONS), ";"));
        assertTrue(clientError.getMessage().contains("*"));
    }

    @Test
    void missingBoundVariableIsReported() throws Exception {
        var plan = LaunchPlanCompiler.compile(
                fixture("versions/1.12.2-trimmed.json"),
                context("windows", "x86_64"),
                client("1.12.2"),
                legacyPublicValues());

        var error = assertThrows(IllegalArgumentException.class,
                () -> plan.renderCommandLine(
                        "java",
                        Map.of(
                                "game_directory", "C:/mc/game",
                                "assets_root", "C:/mc/assets",
                                "auth_access_token", "secret-token"),
                        Path.of(WINDOWS_LIBRARIES),
                        Path.of(WINDOWS_VERSIONS),
                        ";"));

        assertTrue(error.getMessage().contains("natives_directory"));
    }

    private ManifestFragment fixture(String resource) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return parser.parse(stream.readAllBytes());
        }
    }

    private static RuleContext context(String osName, String osArch) {
        return context(osName, osArch, Map.of());
    }

    private static RuleContext context(
            String osName,
            String osArch,
            Map<String, Boolean> features
    ) {
        return new RuleContext(osName, osArch, Optional.empty(), features);
    }

    private static ClientJarRef client(String versionId) {
        return new ClientJarRef(versionId + "/" + versionId + ".jar");
    }

    private static Map<String, String> legacyPublicValues() {
        return Map.of(
                "auth_player_name", "Demo Player",
                "auth_uuid", "uuid-1234",
                "version_name", "1.12.2",
                "version_type", "release",
                "assets_index_name", "1.12",
                "user_type", "legacy"
        );
    }

    private static Map<String, String> modernPublicValues() {
        return Map.of(
                "auth_player_name", "Demo Player",
                "auth_uuid", "uuid-1234",
                "version_name", "1.20.1",
                "version_type", "release",
                "assets_index_name", "5",
                "user_type", "msa",
                "launcher_name", "ZeroPointLauncher",
                "launcher_version", "1.0"
        );
    }

    private static Map<String, String> legacyBoundValues(String nativesDirectory) {
        return Map.of(
                "game_directory", "C:/mc/game",
                "assets_root", "C:/mc/assets",
                "natives_directory", nativesDirectory,
                "auth_access_token", "secret-token"
        );
    }

    private static Map<String, String> modernBoundValues(
            String gameDirectory,
            String assetsRoot,
            String nativesDirectory
    ) {
        return Map.of(
                "game_directory", gameDirectory,
                "assets_root", assetsRoot,
                "natives_directory", nativesDirectory,
                "auth_access_token", "secret-token",
                "auth_xuid", "xuid-1",
                "clientid", "client-1"
        );
    }

    private static List<String> rawPatterns(List<ArgumentTemplate> templates) {
        return templates.stream().map(ArgumentTemplate::rawPattern).toList();
    }

    private static int classpathVariableCount(List<ArgumentTemplate> templates) {
        int count = 0;
        for (ArgumentTemplate template : templates) {
            for (ArgumentTemplate.Part part : template.parts()) {
                if (part instanceof ArgumentTemplate.Part.Variable variable
                        && "classpath".equals(variable.name())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ManifestFragment modernJvm(String... tokens) {
        return new ManifestFragment(
                "custom-modern",
                null,
                "example.Main",
                null,
                new ManifestFragment.ArgumentsBlock(List.of(), direct(tokens)),
                List.of());
    }

    private static List<LaunchArgument> direct(String... values) {
        return Arrays.stream(values)
                .<LaunchArgument>map(LaunchArgument.Direct::new)
                .toList();
    }

    private static void assertCompileSucceeds(String... tokens) {
        var plan = LaunchPlanCompiler.compile(
                modernJvm(tokens),
                context("windows", "x86_64"),
                client("custom-modern"),
                Map.of());
        assertTrue(plan.requiredVariables().contains("classpath"));
    }

    private static void assertCompileFails(String messagePart, String... tokens) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> LaunchPlanCompiler.compile(
                        modernJvm(tokens),
                        context("windows", "x86_64"),
                        client("custom-modern"),
                        Map.of()),
                String.join(" ", tokens));
        assertTrue(error.getMessage().contains(messagePart), error.getMessage());
    }

    private static LaunchPlan compileSimpleLegacyPlan() {
        return compileSimpleLegacyPlan("windows", "x86_64");
    }

    private static LaunchPlan compileSimpleLegacyPlan(String osName, String osArch) {
        var manifest = new ManifestFragment(
                "simple",
                null,
                "example.Main",
                "--flag",
                null,
                List.of());
        return LaunchPlanCompiler.compile(
                manifest,
                context(osName, osArch),
                new ClientJarRef("simple/simple.jar"),
                Map.of());
    }
}
