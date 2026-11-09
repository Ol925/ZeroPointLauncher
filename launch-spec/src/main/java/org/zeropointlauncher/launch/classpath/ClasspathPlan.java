package org.zeropointlauncher.launch.classpath;

import java.util.List;
import java.util.Optional;

/**
 * 有序 classpath 逻辑计划。
 * <p>
 * 除最后一项外，条目相对于 {@code libraries} 根；最后一项是调用方指定的客户端 JAR，
 * 相对于 {@code versions} 根，形如 {@code <versionId>/<versionId>.jar}。所有条目均为
 * 相对路径，保留声明顺序与重复项，不包含平台分隔符。
 */
public record ClasspathPlan(List<String> entries) {
    public ClasspathPlan {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 以输出语义命名的等价视图。 */
    public List<String> relativePaths() {
        return entries;
    }

    /** 装配器产出的计划最后一项；空计划返回空。 */
    public Optional<String> clientJar() {
        return entries.isEmpty()
                ? Optional.empty()
                : Optional.of(entries.getLast());
    }
}
