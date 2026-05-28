package com.zzp.aiagent.rag;

import com.zzp.aiagent.common.PromptTemplate;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.template.model.StyleTemplate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 参考信息装配器：将 RAG 上下文中的参考图/风格模板格式化为 LLM 可理解的提示词段落，
 * 并渲染到 generation_with_rag.st 模板中。
 */
@Component
@Profile("!test")
@Slf4j
@AllArgsConstructor
public class PromptReferenceAssembler {

    private final PromptTemplate promptTemplate;

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

        String explicitRefs = formatExplicitReferences(context);
        String retrievedRefs = formatRetrievedReferences(context);
        String styleTemplate = formatStyleTemplate(context.getStyleTemplate());

        return promptTemplate.render("default", "generation_with_rag",
                "userInput", userInput,
                "explicitReferences", explicitRefs,
                "retrievedReferences", retrievedRefs,
                "styleTemplate", styleTemplate
        );
    }

    /**
     * 构建人类可读的调试信息，描述 RAG 三层各自使用了哪些数据。
     */
    public String buildDebugInfo(RagContext context) {
        StringBuilder sb = new StringBuilder("RAG Layers:\n");

        List<RagContext.ReferencePicture> explicit = context.getExplicitReferences();
        sb.append("  1. 明确参考图: ").append(explicit.size()).append(" 张");
        if (!explicit.isEmpty()) {
            for (RagContext.ReferencePicture ref : explicit) {
                sb.append("\n     - ").append(formatRefDebug(ref));
            }
        }

        List<RagContext.ReferencePicture> retrieved = context.getRetrievedReferences();
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
                pic.put("style", nullToUnknown(ref.profile().style()));
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

        return data;
    }

    // ── 格式化方法 ────────────────────────────────────────────

    private String formatExplicitReferences(RagContext context) {
        List<RagContext.ReferencePicture> refs = context.getExplicitReferences();
        if (refs.isEmpty()) {
            return "（无明确参考图）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            sb.append("参考图").append(i + 1).append("：\n");
            sb.append(formatReferenceDetail(refs.get(i)));
        }
        return sb.toString().trim();
    }

    private String formatRetrievedReferences(RagContext context) {
        List<RagContext.ReferencePicture> refs = context.getRetrievedReferences();
        if (refs.isEmpty()) {
            return "（无历史收藏图参考）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            sb.append("参考图").append(i + 1).append("：\n");
            sb.append(formatReferenceDetail(refs.get(i)));
        }
        return sb.toString().trim();
    }

    private String formatReferenceDetail(RagContext.ReferencePicture ref) {
        GalleryPicture pic = ref.picture();
        PictureAiProfile profile = ref.profile();
        StringBuilder sb = new StringBuilder();
        sb.append("  - 名称：").append(pic.name() != null ? pic.name() : "未命名").append("\n");
        if (pic.tags() != null && !pic.tags().isEmpty()) {
            sb.append("  - 标签：").append(String.join("、", pic.tags())).append("\n");
        }
        if (profile != null) {
            sb.append("  - 风格：").append(nullToUnknown(profile.style())).append("\n");
            sb.append("  - 色彩：").append(nullToUnknown(profile.colors())).append("\n");
            sb.append("  - 构图：").append(nullToUnknown(profile.composition())).append("\n");
            sb.append("  - 光影：").append(nullToUnknown(profile.lighting())).append("\n");
            sb.append("  - 氛围：").append(nullToUnknown(profile.mood())).append("\n");
        } else {
            sb.append("  - 风格：未分析\n");
            sb.append("  - 色彩：未分析\n");
            sb.append("  - 构图：未分析\n");
            sb.append("  - 光影：未分析\n");
            sb.append("  - 氛围：未分析\n");
        }
        return sb.toString();
    }

    private String formatStyleTemplate(StyleTemplate template) {
        if (template == null) {
            return "（无匹配风格模板）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模板名称：").append(template.name()).append("\n");
        sb.append("模板编码：").append(template.code()).append("\n");
        if (template.scene() != null && !template.scene().isBlank()) {
            sb.append("适用场景：").append(template.scene()).append("\n");
        }
        if (template.category() != null && !template.category().isBlank()) {
            sb.append("类别：").append(template.category()).append("\n");
        }
        if (template.keywords() != null && !template.keywords().isEmpty()) {
            sb.append("关键词：").append(String.join("、", template.keywords())).append("\n");
        }
        if (template.prompt() != null && !template.prompt().isBlank()) {
            sb.append("正面提示词：").append(template.prompt()).append("\n");
        }
        if (template.negativePrompt() != null && !template.negativePrompt().isBlank()) {
            sb.append("负面提示词：").append(template.negativePrompt()).append("\n");
        }
        if (template.suggestedDimensions() != null && !template.suggestedDimensions().isBlank()) {
            sb.append("建议尺寸：").append(template.suggestedDimensions()).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatRefDebug(RagContext.ReferencePicture ref) {
        String name = ref.picture().name() != null ? ref.picture().name() : "未命名";
        String style = ref.profile() != null && ref.profile().style() != null
                ? ref.profile().style() : "未分析";
        return name + " (风格: " + style + ")";
    }

    private String nullToUnknown(String value) {
        return (value == null || value.isBlank()) ? "未分析" : value;
    }
}
