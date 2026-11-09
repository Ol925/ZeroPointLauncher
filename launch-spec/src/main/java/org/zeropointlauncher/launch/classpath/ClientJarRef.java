package org.zeropointlauncher.launch.classpath;

import java.util.Objects;

/**
 * 调用方显式指定的客户端 JAR 逻辑引用。
 * <p>
 * 路径相对于 {@code versions} 根，形如 {@code <versionId>/<versionId>.jar}；
 * 只携带相对路径，不检查文件是否存在。构造时执行与 classpath 条目相同的
 * 相对路径安全校验，防止绑定物理根后逃逸。
 */
public record ClientJarRef(String relativePath) {
    public ClientJarRef {
        Objects.requireNonNull(relativePath, "relativePath");
        relativePath = RelativePathRules.requireSafeRelativePath(
                relativePath, "客户端 JAR 相对路径");
    }

    /** 兼容以 path 命名的调用方；与 {@link #relativePath()} 等价。 */
    public String path() {
        return relativePath;
    }
}
