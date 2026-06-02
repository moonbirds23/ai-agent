package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.utils.PromptTemplate;
import com.zzp.aiagent.domain.rag.PackedRagContext;
import com.zzp.aiagent.domain.rag.RagContext;
import com.zzp.aiagent.service.RagContextPacker;
import com.zzp.aiagent.domain.template.StyleTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Prompt 参考信息装配器：将 RAG 上下文中的参考图/风格模板格式化为 LLM 可理解的提示词段落，
 * 并渲染到 generation_with_rag.st 模板中。
 */
@Service
@Profile("!test")
@Slf4j
public class PromptReferenceAssembler {

    private final PromptTemplate promptTemplate;
    private final RagContextPacker packer;

    public PromptReferenceAssembler(PromptTemplate promptTemplate, RagContextPacker packer) {
        this.promptTemplate = promptTemplate;
        this.packer = packer;
    }

    /**
     * 装配增强后的 Prompt。如果上下文为空，直接返回原始用户输入（不包裹模板）。
     *
     * @param userInput    用户原始输入
     * @param context      RAG 增强上下文
     * @return 装配后的完整提示词
     */
    public String assemble(String userInput, RagContext context) {
        if (context.isEmpty()) {
            return userInput;
        }

        PackedRagContext packed = context.getPackedContext();
        if (packed == null) {
            packed = packer.pack(context, null);
        }

        return promptTemplate.render("default", "generation_with_rag",
                "userInput", userInput,
                "explicitReferences", packed.explicitReferencesText(),
                "retrievedReferences", packed.retrievedReferencesText(),
                "styleTemplate", packed.styleTemplateText()
        );
    }

    /**
     * 构建人类可读的调试信息，描述 RAG 三层各自使用了哪些数据。
     */
    public String buildDebugInfo(RagContext context) {
        StringBuilder sb = new StringBuilder("RAG Layers:\n");

        java.util.List<RagContext.ReferencePicture> explicit = context.getExplicitReferences();
        sb.append("  1. 明确参考图: ").append(explicit.size()).append(" 张");
        if (!explicit.isEmpty()) {
            for (RagContext.ReferencePicture ref : explicit) {
                sb.append("\n     - ").append(formatRefDebug(ref));
            }
        }

        java.util.List<RagContext.ReferencePicture> retrieved = context.getRetrievedReferences();
        sb.append("\n  2. RAG检索图: ").append(retrieved.size()).append(" 张");
        if (!retrieved.isEmpty()) {
            for (RagContext.ReferencePicture ref : retrieved) {
                sb.append("\n     - ").append(formatRefDebug(ref));
            }
        }

        StyleTemplate tmpl = context.getStyleTemplate();
        sb.append("\n  3. 风格模板: ");
        if (tmpl != null) {
            sb.append(tmpl.name()).append(" (").append(tmpl.code()).append(")");
        } else {
            sb.append("无");
        }

        return sb.toString();
    }

    /**
     * 构建结构化调试数据，供前端右侧面板展示。
     */
    public java.util.Map<String, Object> buildDebugData(RagContext context, String enhancedPrompt) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();

        // 检索到的参考图
        java.util.List<java.util.Map<String, Object>> retrievedPics = new java.util.ArrayList<>();
        for (RagContext.ReferencePicture ref : context.getRetrievedReferences()) {
            java.util.Map<String, Object> pic = new java.util.LinkedHashMap<>();
            pic.put("pictureId", ref.picture().id());
            pic.put("name", ref.picture().name() != null ? ref.picture().name() : "未命名");
            if (ref.profile() != null) {
                String style = ref.profile().style();
                pic.put("style", (style == null || style.isBlank()) ? "未分析" : style);
            }
            retrievedPics.add(pic);
        }
        data.put("retrievedPictures", retrievedPics);

        // 明确的参考图
        java.util.List<java.util.Map<String, Object>> explicitPics = new java.util.ArrayList<>();
        for (RagContext.ReferencePicture ref : context.getExplicitReferences()) {
            java.util.Map<String, Object> pic = new java.util.LinkedHashMap<>();
            pic.put("pictureId", ref.picture().id());
            pic.put("name", ref.picture().name() != null ? ref.picture().name() : "未命名");
            explicitPics.add(pic);
        }
        data.put("explicitPictures", explicitPics);

        // 命中的风格模板
        StyleTemplate tmpl = context.getStyleTemplate();
        if (tmpl != null) {
            java.util.Map<String, Object> t = new java.util.LinkedHashMap<>();
            t.put("code", tmpl.code());
            t.put("name", tmpl.name());
            t.put("category", tmpl.category());
            t.put("scene", tmpl.scene());
            data.put("matchedTemplate", t);
        }

        // 最终增强Prompt
        if (enhancedPrompt != null && !enhancedPrompt.isBlank()) {
            data.put("enhancedPrompt", enhancedPrompt);
        }

        // RAG 增强链路追踪数据（来自 RagContext.trace）
        Map<String, Object> enhanceTrace = context.getTrace();
        if (enhanceTrace != null && !enhanceTrace.isEmpty()) {
            if (enhanceTrace.containsKey("rewrittenQuery")) {
                data.put("rewrite", enhanceTrace.get("rewrittenQuery"));
            }
            if (enhanceTrace.containsKey("resolvedReferenceMode")) {
                data.put("referenceMode", enhanceTrace.get("resolvedReferenceMode"));
            }
            if (enhanceTrace.containsKey("selectedSummary")) {
                data.put("selected", enhanceTrace.get("selectedSummary"));
            }
        }

        return data;
    }

    private String formatRefDebug(RagContext.ReferencePicture ref) {
        String name = ref.picture().name() != null ? ref.picture().name() : "未命名";
        String style = ref.profile() != null && ref.profile().style() != null
                ? ref.profile().style() : "未分析";
        return name + " (风格: " + style + ")";
    }
}
