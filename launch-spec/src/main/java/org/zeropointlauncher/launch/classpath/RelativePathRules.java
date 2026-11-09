package org.zeropointlauncher.launch.classpath;

import java.util.Objects;

/**
 * classpath 相关相对路径的包内安全规则。
 * <p>
 * 只做纯字符串校验：拒绝绝对路径、Windows 盘符/冒号、反斜杠、空段以及
 * {@code .}/{@code ..} 穿越段；不访问文件系统，也不做平台路径解析。
 */
final class RelativePathRules {
    private RelativePathRules() {
    }

    static String requireSafeRelativePath(String path, String description) {
        Objects.requireNonNull(path, description);

        String normalized = path.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(description + " 不能为空");
        }
        if (normalized.startsWith("/")) {
            throw unsafe(description, normalized, "不能以 '/' 开头");
        }
        if (normalized.indexOf('\\') >= 0) {
            throw unsafe(description, normalized, "不能包含反斜杠 '\\'");
        }
        if (normalized.indexOf(':') >= 0) {
            throw unsafe(description, normalized, "不能包含 Windows 盘符或冒号 ':'");
        }

        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty()) {
                throw unsafe(description, normalized, "不能包含空段");
            }
            String candidate = segment.strip();
            if (candidate.isEmpty()) {
                throw unsafe(description, normalized, "不能包含空段");
            }
            if (candidate.equals(".") || candidate.equals("..")) {
                throw unsafe(description, normalized, "不能包含路径穿越段 '.' 或 '..'");
            }
        }

        return normalized;
    }

    static String requireSafeSegment(String value, String description) {
        Objects.requireNonNull(value, description);

        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(description + " 不能为空");
        }
        if (normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || normalized.indexOf(':') >= 0) {
            throw unsafe(description, normalized, "不能包含路径分隔符或冒号");
        }
        if (normalized.equals(".") || normalized.equals("..")) {
            throw unsafe(description, normalized, "不能为路径穿越段 '.' 或 '..'");
        }

        return normalized;
    }

    private static IllegalArgumentException unsafe(
            String description,
            String value,
            String reason
    ) {
        return new IllegalArgumentException(
                description + " 不是安全的相对路径（" + reason + "）: " + value);
    }
}
