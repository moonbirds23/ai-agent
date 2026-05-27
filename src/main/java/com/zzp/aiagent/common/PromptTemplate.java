package com.zzp.aiagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板引擎。启动时扫描 classpath:prompts/ 目录下的 .st 文件，
 * 按 {mode}/{name}.st 两级组织，通过 {变量名} 语法替换占位符。
 *
 * <pre>
 * // 渲染 default/system.st，变量 outputFormat → "{\"type\":\"json\"}"
 * promptTemplate.render("default", "system", "outputFormat", "{\"type\":\"json\"}");
 * </pre>
 */
@Component
@Slf4j
public class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final String TEMPLATE_BASE = "classpath:prompts/*/**.st";

    /** mode → (name → template) */
    private final Map<String, Map<String, String>> store = new ConcurrentHashMap<>();

    public PromptTemplate() {
        load();
    }

    private void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(TEMPLATE_BASE);
            for (Resource res : resources) {
                String path = res.getURI().toString();
                // path: .../prompts/{mode}/{name}.st
                String[] parts = path.split("prompts/");
                if (parts.length < 2) continue;
                String relative = parts[1];  // "default/system.st"
                int lastSlash = relative.lastIndexOf('/');
                if (lastSlash < 0) continue;
                String mode = relative.substring(0, lastSlash);          // "default"
                String name = relative.substring(lastSlash + 1);          // "system.st"
                if (name.endsWith(".st")) {
                    name = name.substring(0, name.length() - 3);          // "system"
                }
                String content = res.getContentAsString(StandardCharsets.UTF_8);
                store.computeIfAbsent(mode, k -> new ConcurrentHashMap<>()).put(name, content);
                log.info("[PromptTemplate] 加载 {}/{} ({} 字符)", mode, name, content.length());
            }
        } catch (IOException e) {
            log.error("[PromptTemplate] 扫描模板失败", e);
        }
    }

    /**
     * 渲染模板。变量按 key1, value1, key2, value2... 顺序传入。
     *
     * @param mode 模式目录名，未找到时回退到 "default"
     * @param name 模板名（不含 .st 后缀）
     * @param keyValues 键值对，key → value 替换 {key}
     */
    public String render(String mode, String name, String... keyValues) {
        String template = resolve(mode, name);
        if (template == null) {
            return "";
        }
        Map<String, String> vars = Map.of();  // 需要可变副本
        if (keyValues.length % 2 != 0) {
            log.warn("[PromptTemplate] render({}/{}) 键值对数量为奇数，忽略最后一个key", mode, name);
        }
        // 用 StringBuilder 逐步替换，避免 Map.of 不可变
        String result = template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", keyValues[i + 1]);
        }
        return result;
    }

    private String resolve(String mode, String name) {
        Map<String, String> modeTemplates = store.get(mode);
        if (modeTemplates != null) {
            String t = modeTemplates.get(name);
            if (t != null) return t;
        }
        // 回退到 default
        if (!"default".equals(mode)) {
            Map<String, String> defaultTemplates = store.get("default");
            if (defaultTemplates != null) {
                String t = defaultTemplates.get(name);
                if (t != null) {
                    log.debug("[PromptTemplate] {}/{} 不存在，回退到 default/{}", mode, name, name);
                    return t;
                }
            }
        }
        log.warn("[PromptTemplate] 模板 {}/{} 未找到", mode, name);
        return null;
    }
}
