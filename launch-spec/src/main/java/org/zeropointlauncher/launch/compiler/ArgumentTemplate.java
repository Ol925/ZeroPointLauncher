package org.zeropointlauncher.launch.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个启动参数 token 的不可变模板。
 * <p>
 * 变量只做单遍字面替换：替换值中的空格、反斜杠、{@code $} 或 {@code ${...}}
 * 都不会被重新分词或递归展开。
 */
public record ArgumentTemplate(String rawPattern, List<Part> parts) {
    public sealed interface Part {
        record Literal(String text) implements Part {
            public Literal {
                Objects.requireNonNull(text, "text");
            }
        }

        record Variable(String name) implements Part {
            public Variable {
                Objects.requireNonNull(name, "name");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("变量名不能为空");
                }
            }
        }
    }

    public ArgumentTemplate {
        Objects.requireNonNull(rawPattern, "rawPattern");
        parts = List.copyOf(parts);
    }

    /**
     * 单遍解析单个 token：扫描 {@code ${} 与 {@code }}，其余内容作为字面段。
     *
     * @throws IllegalArgumentException 占位符为空或未闭合
     */
    public static ArgumentTemplate parse(String token) {
        Objects.requireNonNull(token, "token");

        var parsed = new ArrayList<Part>();
        int cursor = 0;
        while (cursor < token.length()) {
            int start = token.indexOf("${", cursor);
            if (start < 0) {
                parsed.add(new Part.Literal(token.substring(cursor)));
                break;
            }
            if (start > cursor) {
                parsed.add(new Part.Literal(token.substring(cursor, start)));
            }

            int end = token.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException(
                        "未闭合的占位符: " + token.substring(start));
            }

            String name = token.substring(start + 2, end);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("占位符变量名不能为空: " + token);
            }
            parsed.add(new Part.Variable(name));
            cursor = end + 1;
        }

        return new ArgumentTemplate(token, parsed);
    }

    /**
     * 用给定变量表渲染为单个字面 token。
     *
     * @throws IllegalArgumentException 变量未绑定
     */
    public String render(Map<String, String> resolvedValues) {
        Objects.requireNonNull(resolvedValues, "resolvedValues");

        var rendered = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Part.Literal literal -> rendered.append(literal.text());
                case Part.Variable variable -> {
                    String value = resolvedValues.get(variable.name());
                    if (value == null) {
                        throw new IllegalArgumentException(
                                "未绑定变量: " + variable.name());
                    }
                    rendered.append(value);
                }
            }
        }
        return rendered.toString();
    }
}
