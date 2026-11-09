package org.zeropointlauncher.launch.compiler;

import org.zeropointlauncher.launch.classpath.ClasspathPlan;
import org.zeropointlauncher.launch.model.RuleContext;
import org.zeropointlauncher.launch.resolve.NativeExtractRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

        requireSafePhysicalPath(javaExecutable, "Java 可执行文件", pathSeparator);
        requireSafePhysicalPath(librariesRoot.toString(), "libraries 根", pathSeparator);
        requireSafePhysicalPath(versionsRoot.toString(), "versions 根", pathSeparator);

        var entries = classpathPlan.entries();
        var classpathValues = new ArrayList<String>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Path root = i == entries.size() - 1 ? versionsRoot : librariesRoot;
            String resolved = root.resolve(entries.get(i)).toString();
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

        var command = new ArrayList<String>();
        command.add(javaExecutable);
        for (ArgumentTemplate template : jvmTemplates) {
            command.add(template.render(values));
        }
        command.add(mainClass);
        for (ArgumentTemplate template : gameTemplates) {
            command.add(template.render(values));
        }
        return List.copyOf(command);
    }

    /**
     * 日志脱敏命令视图：仅将 {@code auth_access_token} 的实际值替换为 {@code ***}。
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
        if (sanitized.get("auth_access_token") != null) {
            sanitized.put("auth_access_token", "***");
        }
        return renderCommandLine(
                javaExecutable, sanitized, librariesRoot, versionsRoot, pathSeparator);
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
