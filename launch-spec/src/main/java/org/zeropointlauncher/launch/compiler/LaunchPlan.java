package org.zeropointlauncher.launch.compiler;

import org.zeropointlauncher.launch.classpath.ClasspathPlan;
import org.zeropointlauncher.launch.model.RuleContext;
import org.zeropointlauncher.launch.resolve.NativeExtractRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 不可变启动执行计划。
 * <p>
 * 编译阶段只保存参数模板与逻辑 classpath；物理根、classpath 字符串和会话秘密
 * 由 {@link #renderCommandLine} 在执行边界绑定。
 */
public record LaunchPlan(
        String manifestId,
        RuleContext targetEnvironment,
        String mainClass,
        List<ArgumentTemplate> jvmTemplates,
        List<ArgumentTemplate> gameTemplates,
        ClasspathPlan classpathPlan,
        List<NativeExtractRequest> legacyNativeRequests,
        Set<String> requiredVariables
) {
    private static final Set<String> SENSITIVE_VARIABLES = Set.of(
            "auth_access_token",
            "auth_session"
    );

    public LaunchPlan {
        Objects.requireNonNull(manifestId, "manifestId");
        Objects.requireNonNull(targetEnvironment, "targetEnvironment");
        Objects.requireNonNull(mainClass, "mainClass");
        jvmTemplates = List.copyOf(jvmTemplates);
        gameTemplates = List.copyOf(gameTemplates);
        Objects.requireNonNull(classpathPlan, "classpathPlan");
        legacyNativeRequests = List.copyOf(legacyNativeRequests);
        requiredVariables = Set.copyOf(requiredVariables);
    }

    /**
     * 宿主物理绑定器：校验路径、注入 classpath、检查必需变量并组装最终 argv。
     * <p>
     * 返回顺序固定为 {@code [javaExecutable] -> [JVM args] -> [mainClass] -> [game args]}。
     */
    public List<String> renderCommandLine(
            String javaExecutable,
            Map<String, String> boundValues,
            Path librariesRoot,
            Path versionsRoot,
            String pathSeparator
    ) {
        Objects.requireNonNull(javaExecutable, "javaExecutable");
        Objects.requireNonNull(boundValues, "boundValues");
        Objects.requireNonNull(librariesRoot, "librariesRoot");
        Objects.requireNonNull(versionsRoot, "versionsRoot");
        Objects.requireNonNull(pathSeparator, "pathSeparator");
        if (pathSeparator.isEmpty()) {
            throw new IllegalArgumentException("pathSeparator 不能为空");
        }

        String expectedSeparator = requiredPathSeparator(targetEnvironment.osName());
        if (!expectedSeparator.equals(pathSeparator)) {
            throw new IllegalArgumentException(
                    "pathSeparator 与目标 OS " + targetEnvironment.osName()
                            + " 不匹配，期望 '" + expectedSeparator + "': "
                            + pathSeparator);
        }

        requireSafePhysicalPath(javaExecutable, "Java 可执行文件", pathSeparator);
        requireSafePhysicalPath(librariesRoot.toString(), "libraries 根", pathSeparator);
        requireSafePhysicalPath(versionsRoot.toString(), "versions 根", pathSeparator);

        var entries = classpathPlan.entries();
        var classpathValues = new ArrayList<String>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            requireNoWildcard(entry, "Classpath 条目");
            Path root = i == entries.size() - 1 ? versionsRoot : librariesRoot;
            String resolved = root.resolve(entry).toString();
            requireNoWildcard(resolved, "解析后的 Classpath 路径");
            requireSafePhysicalPath(resolved, "Classpath 条目", pathSeparator);
            classpathValues.add(resolved);
        }

        var values = new HashMap<String, String>(boundValues);
        values.put("classpath", String.join(pathSeparator, classpathValues));

        var missing = new TreeSet<String>();
        for (String name : requiredVariables) {
            if (values.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "缺少执行期变量: " + String.join(", ", missing));
        }

        var jvmArguments = new ArrayList<String>(jvmTemplates.size());
        for (ArgumentTemplate template : jvmTemplates) {
            jvmArguments.add(template.render(values));
        }
        validateFinalJvmArguments(jvmArguments);

        var command = new ArrayList<String>();
        command.add(javaExecutable);
        command.addAll(jvmArguments);
        command.add(mainClass);
        for (ArgumentTemplate template : gameTemplates) {
            command.add(template.render(values));
        }
        return List.copyOf(command);
    }

    /**
     * 日志脱敏命令视图：将 {@code auth_access_token} 与 {@code auth_session}
     * 的实际值替换为 {@code ***}，包括嵌入在其他参数中的引用。
     */
    public List<String> renderSanitizedCommandLine(
            String javaExecutable,
            Map<String, String> boundValues,
            Path librariesRoot,
            Path versionsRoot,
            String pathSeparator
    ) {
        Objects.requireNonNull(boundValues, "boundValues");

        var sanitized = new HashMap<String, String>(boundValues);
        for (String name : SENSITIVE_VARIABLES) {
            if (sanitized.get(name) != null) {
                sanitized.put(name, "***");
            }
        }
        return renderCommandLine(
                javaExecutable, sanitized, librariesRoot, versionsRoot, pathSeparator);
    }

    private static String requiredPathSeparator(String osName) {
        if (osName == null) {
            throw new IllegalArgumentException("目标环境缺少 OS 名称");
        }
        return switch (osName.strip().toLowerCase(Locale.ROOT)) {
            case "windows" -> ";";
            case "linux", "osx", "macos", "darwin", "mac" -> ":";
            default -> throw new IllegalArgumentException(
                    "无法为目标 OS 确定 classpath 分隔符: " + osName);
        };
    }

    private static void requireNoWildcard(String value, String label) {
        if (value.endsWith("*")) {
            throw new IllegalArgumentException(
                    label + " 不能以 '*' 结尾: " + value);
        }
    }

    private static void validateFinalJvmArguments(List<String> jvmArguments) {
        int classpathEntries = 0;
        for (int i = 0; i < jvmArguments.size(); i++) {
            String argument = jvmArguments.get(i);

            if ("-jar".equals(argument)) {
                throw new IllegalArgumentException(
                        "JVM 参数在索引 " + i + " 处包含了禁止的 -jar 选项");
            }
            if ("-Djava.class.path".equals(argument)
                    || argument.startsWith("-Djava.class.path=")) {
                throw new IllegalArgumentException(
                        "JVM 参数在索引 " + i + " 处试图覆盖 java.class.path");
            }
            if (isClasspathEntry(argument)) {
                classpathEntries++;
            }
        }

        if (classpathEntries != 1) {
            throw new IllegalArgumentException(
                    "最终 JVM 参数必须包含且仅包含一个 Classpath 入口，实际: "
                            + classpathEntries);
        }
    }

    private static boolean isClasspathEntry(String argument) {
        return argument.equals("-cp")
                || argument.equals("-classpath")
                || argument.equals("--class-path")
                || argument.startsWith("--class-path=");
    }

    private static void requireSafePhysicalPath(
            String value,
            String label,
            String pathSeparator
    ) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        if (value.contains(pathSeparator)) {
            throw new IllegalArgumentException(
                    label + " 不能包含目标平台分隔符 '" + pathSeparator + "': " + value);
        }
        if (";".equals(pathSeparator)) {
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == ':'
                        && !(i == 1 && isAsciiLetter(value.charAt(0)))) {
                    throw new IllegalArgumentException(
                            label + " 包含非法冒号: " + value);
                }
            }
        }
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }
}
