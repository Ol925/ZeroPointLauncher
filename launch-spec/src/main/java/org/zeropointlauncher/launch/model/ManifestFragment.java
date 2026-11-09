package org.zeropointlauncher.launch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.zeropointlauncher.launch.json.JsonSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestFragment(
        String id,
        @JsonDeserialize(using = JsonSupport.TextualOnlyDeserializer.class) String inheritsFrom,
        @JsonDeserialize(using = JsonSupport.TextualOnlyDeserializer.class) String mainClass,
        @JsonDeserialize(using = JsonSupport.TextualOnlyDeserializer.class) String minecraftArguments,
        ArgumentsBlock arguments,
        @JsonSetter(nulls = Nulls.AS_EMPTY) List<Library> libraries
) {
    public ManifestFragment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("清单缺少 id");
        }
        id = id.strip();
        inheritsFrom = blankToNull(inheritsFrom);
        mainClass = blankToNull(mainClass);
        minecraftArguments = blankToNull(minecraftArguments);
        libraries = libraries == null
                ? List.of()
                : libraries.stream().filter(Objects::nonNull).toList();
    }

    /**
     * 求值器唯一入口：1.13+ 的 arguments.game 非空时优先；否则按旧版语义把
     * minecraftArguments 按空白切分，双引号或单引号内的空白保留在同一 token 中，
     * 显式空引号也会保留为空 token。不依赖 shell，也不处理反斜杠转义。
     */
    public List<LaunchArgument> gameArguments() {
        if (arguments != null && !arguments.game().isEmpty()) {
            return arguments.game();
        }
        if (minecraftArguments == null) {
            return List.of();
        }
        return tokenizeLegacyArguments(minecraftArguments).stream()
                .<LaunchArgument>map(LaunchArgument.Direct::new)
                .toList();
    }

    public List<LaunchArgument> jvmArguments() {
        return arguments == null ? List.of() : arguments.jvm();
    }

    private static List<String> tokenizeLegacyArguments(String raw) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;

        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else {
                    current.append(value);
                }
                tokenStarted = true;
            } else if (value == '"' || value == '\'') {
                quote = value;
                tokenStarted = true;
            } else if (Character.isWhitespace(value)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(value);
                tokenStarted = true;
            }
        }

        if (quote != 0) {
            throw new IllegalArgumentException(
                    "minecraftArguments 包含未闭合引号: " + raw);
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    public record ArgumentsBlock(
            @JsonSetter(nulls = Nulls.AS_EMPTY) List<LaunchArgument> game,
            @JsonSetter(nulls = Nulls.AS_EMPTY) List<LaunchArgument> jvm
    ) {
        public ArgumentsBlock {
            game = game == null
                    ? List.of()
                    : game.stream().filter(Objects::nonNull).toList();
            jvm = jvm == null
                    ? List.of()
                    : jvm.stream().filter(Objects::nonNull).toList();
        }
    }
}
