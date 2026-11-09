package org.zeropointlauncher.launch.compiler;

import org.zeropointlauncher.launch.classpath.ClasspathAssembler;
import org.zeropointlauncher.launch.classpath.ClasspathPlan;
import org.zeropointlauncher.launch.classpath.ClientJarRef;
import org.zeropointlauncher.launch.model.ManifestFragment;
import org.zeropointlauncher.launch.model.RuleContext;
import org.zeropointlauncher.launch.resolve.LegacyNativeSelector;
import org.zeropointlauncher.launch.resolve.ManifestSelector;
import org.zeropointlauncher.launch.resolve.NativeExtractRequest;
import org.zeropointlauncher.launch.resolve.NativeSelectionResult;
import org.zeropointlauncher.launch.resolve.SelectedLaunchInputs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将有效清单、目标环境与公开变量编译为不可变 {@link LaunchPlan}。
 * <p>
 * 纯函数：不访问网络、磁盘或系统属性；宿主准备值和会话秘密只记录为所需变量。
 */
public final class LaunchPlanCompiler {
    private static final Set<String> PUBLIC_VARIABLES = Set.of(
            "auth_player_name",
            "auth_uuid",
            "version_name",
            "version_type",
            "assets_index_name",
            "resolution_width",
            "resolution_height",
            "user_type",
            "launcher_name",
            "launcher_version"
    );

    private static final Set<String> HOST_VARIABLES = Set.of(
            "classpath",
            "game_directory",
            "assets_root",
            "natives_directory",
            "game_assets",
            "quickPlayPath",
            "quickPlaySingleplayer",
            "quickPlayMultiplayer",
            "quickPlayRealms"
    );

    private static final Set<String> SESSION_VARIABLES = Set.of(
            "auth_access_token",
            "auth_xuid",
            "clientid",
            "auth_session",
            "user_properties",
            "profile_properties"
    );

    private static final Set<String> KNOWN_VARIABLES = knownVariables();

    private LaunchPlanCompiler() {
    }

    /**
     * 编译启动计划。
     *
     * @param effectiveManifest 已完成继承合并的有效清单
     * @param target            目标游戏环境
     * @param clientJar         调用方指定的客户端 JAR
     * @param publicValues      编译期公开值，例如玩家名、版本名与分辨率
     * @return 不可变启动计划
     * @throws IllegalArgumentException 主类缺失、Native 选择失败、参数变量未知、
     *                                  公开值缺失或现代 Classpath 入口不合法
     */
    public static LaunchPlan compile(
            ManifestFragment effectiveManifest,
            RuleContext target,
            ClientJarRef clientJar,
            Map<String, String> publicValues
    ) {
        Objects.requireNonNull(effectiveManifest, "effectiveManifest");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(clientJar, "clientJar");
        Objects.requireNonNull(publicValues, "publicValues");

        String mainClass = effectiveManifest.mainClass();
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少有效主类: " + effectiveManifest.id());
        }

        SelectedLaunchInputs selected = ManifestSelector.select(effectiveManifest, target);
        ClasspathPlan classpathPlan = ClasspathAssembler.assemble(selected, clientJar);
        NativeSelectionResult nativeSelection = LegacyNativeSelector.select(
                selected.libraries(), target);
        if (!nativeSelection.issues().isEmpty()) {
            var details = nativeSelection.issues().stream()
                    .map(issue -> issue.libraryName()
                            + " (" + issue.targetOsKey() + "): " + issue.message())
                    .toList();
            throw new IllegalArgumentException(
                    "Native 选择失败: " + String.join("; ", details));
        }
        List<NativeExtractRequest> legacyNativeRequests = nativeSelection.requests();

        validatePublicValues(publicValues);

        boolean legacyMode = effectiveManifest.minecraftArguments() != null
                && effectiveManifest.arguments() == null;
        List<String> jvmTokens;
        if (legacyMode) {
            jvmTokens = new ArrayList<>(selected.jvmTokens());
            jvmTokens.add("-cp");
            jvmTokens.add("${classpath}");
            if (!legacyNativeRequests.isEmpty()) {
                jvmTokens.add("-Djava.library.path=${natives_directory}");
            }
        } else {
            jvmTokens = selected.jvmTokens();
        }

        List<ArgumentTemplate> jvmTemplates = parseTemplates(jvmTokens);
        if (!legacyMode) {
            validateModernClasspath(jvmTemplates);
        }

        var requiredVariables = new LinkedHashSet<String>();
        List<ArgumentTemplate> resolvedJvmTemplates = resolveTemplates(
                jvmTemplates, publicValues, requiredVariables);
        List<ArgumentTemplate> resolvedGameTemplates = resolveTemplates(
                parseTemplates(selected.gameTokens()), publicValues, requiredVariables);

        return new LaunchPlan(
                effectiveManifest.id(),
                target,
                mainClass,
                resolvedJvmTemplates,
                resolvedGameTemplates,
                classpathPlan,
                legacyNativeRequests,
                Set.copyOf(requiredVariables)
        );
    }

    private static List<ArgumentTemplate> parseTemplates(List<String> tokens) {
        var templates = new ArrayList<ArgumentTemplate>(tokens.size());
        for (String token : tokens) {
            templates.add(ArgumentTemplate.parse(token));
        }
        return List.copyOf(templates);
    }

    private static List<ArgumentTemplate> resolveTemplates(
            List<ArgumentTemplate> templates,
            Map<String, String> publicValues,
            Set<String> requiredVariables
    ) {
        var resolved = new ArrayList<ArgumentTemplate>(templates.size());
        for (ArgumentTemplate template : templates) {
            var parts = new ArrayList<ArgumentTemplate.Part>(template.parts().size());
            for (ArgumentTemplate.Part part : template.parts()) {
                switch (part) {
                    case ArgumentTemplate.Part.Literal literal -> parts.add(literal);
                    case ArgumentTemplate.Part.Variable variable -> {
                        String name = variable.name();
                        if (!KNOWN_VARIABLES.contains(name)) {
                            throw new IllegalArgumentException(
                                    "未知参数变量: " + name);
                        }
                        if (PUBLIC_VARIABLES.contains(name)) {
                            String value = publicValues.get(name);
                            if (value == null) {
                                throw new IllegalArgumentException(
                                        "缺少编译期公开变量: " + name);
                            }
                            parts.add(new ArgumentTemplate.Part.Literal(value));
                        } else {
                            requiredVariables.add(name);
                            parts.add(variable);
                        }
                    }
                }
            }
            resolved.add(new ArgumentTemplate(template.rawPattern(), parts));
        }
        return List.copyOf(resolved);
    }

    private static void validatePublicValues(Map<String, String> publicValues) {
        for (Map.Entry<String, String> entry : publicValues.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                throw new IllegalArgumentException(
                        "publicValues 不能包含 null 变量名");
            }
            if (!KNOWN_VARIABLES.contains(name)) {
                throw new IllegalArgumentException("未知参数变量: " + name);
            }
            if (!PUBLIC_VARIABLES.contains(name)) {
                throw new IllegalArgumentException(
                        "变量 " + name + " 必须在执行阶段绑定");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "编译期公开变量不能为 null: " + name);
            }
        }
    }

    private static void validateModernClasspath(List<ArgumentTemplate> jvmTemplates) {
        int entries = 0;
        for (int i = 0; i < jvmTemplates.size(); i++) {
            ArgumentTemplate template = jvmTemplates.get(i);
            String rawPattern = template.rawPattern();

            if (isClasspathFlag(rawPattern)) {
                if (entries > 0) {
                    throw duplicateClasspath();
                }
                if (i + 1 >= jvmTemplates.size()
                        || !isExactlyClasspathVariable(jvmTemplates.get(i + 1))) {
                    throw hardcodedClasspath(rawPattern);
                }
                entries++;
                i++;
                continue;
            }

            if (rawPattern.startsWith("--class-path=")) {
                if (entries > 0) {
                    throw duplicateClasspath();
                }
                if (!isExactlyEqualsClasspath(template)) {
                    throw hardcodedClasspath(rawPattern);
                }
                entries++;
                continue;
            }

            if (rawPattern.startsWith("-cp=")
                    || rawPattern.startsWith("-classpath=")) {
                throw new IllegalArgumentException(
                        "不支持的 Classpath 形式: " + rawPattern);
            }

            if (containsClasspathVariable(template)) {
                throw new IllegalArgumentException(
                        "Classpath 入口位置非法: " + rawPattern);
            }
        }

        if (entries != 1) {
            throw new IllegalArgumentException(
                    "现代 JVM 参数必须包含且仅包含一个 Classpath 入口");
        }
    }

    private static boolean isClasspathFlag(String rawPattern) {
        return rawPattern.equals("-cp")
                || rawPattern.equals("-classpath")
                || rawPattern.equals("--class-path");
    }

    private static boolean isExactlyClasspathVariable(ArgumentTemplate template) {
        return template.parts().size() == 1
                && template.parts().getFirst()
                        instanceof ArgumentTemplate.Part.Variable variable
                && "classpath".equals(variable.name());
    }

    private static boolean isExactlyEqualsClasspath(ArgumentTemplate template) {
        return template.parts().size() == 2
                && template.parts().get(0)
                        instanceof ArgumentTemplate.Part.Literal literal
                && "--class-path=".equals(literal.text())
                && template.parts().get(1)
                        instanceof ArgumentTemplate.Part.Variable variable
                && "classpath".equals(variable.name());
    }

    private static boolean containsClasspathVariable(ArgumentTemplate template) {
        for (ArgumentTemplate.Part part : template.parts()) {
            if (part instanceof ArgumentTemplate.Part.Variable variable
                    && "classpath".equals(variable.name())) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException duplicateClasspath() {
        return new IllegalArgumentException(
                "现代 JVM 参数包含重复的 Classpath 入口");
    }

    private static IllegalArgumentException hardcodedClasspath(String rawPattern) {
        return new IllegalArgumentException(
                "Classpath 入口必须使用 ${classpath}，禁止硬编码: " + rawPattern);
    }

    private static Set<String> knownVariables() {
        var known = new HashSet<String>();
        known.addAll(PUBLIC_VARIABLES);
        known.addAll(HOST_VARIABLES);
        known.addAll(SESSION_VARIABLES);
        return Set.copyOf(known);
    }
}
