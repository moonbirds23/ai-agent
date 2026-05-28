package com.zzp.aiagent.rag.enhance;

import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.RagProperties;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.template.model.StyleTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
@Slf4j
public class RagContextPackerImpl implements RagContextPacker {

    private final RagProperties ragProperties;

    public RagContextPackerImpl(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public PackedRagContext pack(RagContext context, RagSearchCriteria criteria) {
        String referenceMode = criteria != null ? criteria.referenceMode() : null;

        String explicitText = formatReferences(context.getExplicitReferences(), referenceMode);
        String retrievedText = formatReferences(context.getRetrievedReferences(), referenceMode);
        String templateText = formatTemplate(context.getStyleTemplate());

        int explicitLen = explicitText.length();
        int retrievedLen = retrievedText.length();
        int templateLen = templateText.length();
        int total = explicitLen + retrievedLen + templateLen;

        int maxChars = ragProperties.maxContextChars();
        if (total > maxChars) {
            log.info("[ContextPacker] 上下文超限 {} > {} chars，执行截断", total, maxChars);
            // Truncate retrieved first, then explicit — explicit has higher priority
            int excess = total - maxChars;
            if (retrievedLen > 0 && excess > 0) {
                if (retrievedLen >= excess) {
                    retrievedText = truncateText(retrievedText, retrievedLen - excess);
                    excess = 0;
                } else {
                    excess -= retrievedLen;
                    retrievedText = retrievedText.substring(0, Math.min(50, retrievedLen)) + "\n...";
                }
            }
            if (explicitLen > 0 && excess > 0) {
                if (explicitLen >= excess) {
                    explicitText = truncateText(explicitText, explicitLen - excess);
                } else {
                    explicitText = explicitText.substring(0, Math.min(50, explicitLen)) + "\n...";
                }
            }
            total = explicitText.length() + retrievedText.length() + templateText.length();
        }

        return new PackedRagContext(explicitText, retrievedText, templateText, total);
    }

    private String formatReferences(List<RagContext.ReferencePicture> refs, String referenceMode) {
        if (refs == null || refs.isEmpty()) {
            return "（无参考图）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            RagContext.ReferencePicture ref = refs.get(i);
            sb.append("参考图").append(i + 1).append("：\n");
            sb.append(formatReferenceDetail(ref, referenceMode));
        }
        return sb.toString().trim();
    }

    private String formatReferenceDetail(RagContext.ReferencePicture ref, String referenceMode) {
        GalleryPicture pic = ref.picture();
        PictureAiProfile profile = ref.profile();

        StringBuilder sb = new StringBuilder();
        sb.append("  - 名称：").append(pic.name() != null ? pic.name() : "未命名").append("\n");

        if (pic.tags() != null && !pic.tags().isEmpty()) {
            sb.append("  - 标签：").append(String.join("、", pic.tags())).append("\n");
        }

        if (profile == null) {
            sb.append("  - 画像：未分析\n");
            return sb.toString();
        }

        // Apply referenceMode trimming
        if (referenceMode == null || "overall".equalsIgnoreCase(referenceMode)) {
            sb.append("  - 主题：").append(nullToUnknown(profile.subject())).append("\n");
            sb.append("  - 风格：").append(nullToUnknown(profile.style())).append("\n");
            sb.append("  - 色彩：").append(nullToUnknown(profile.colors())).append("\n");
            sb.append("  - 构图：").append(nullToUnknown(profile.composition())).append("\n");
            sb.append("  - 光影：").append(nullToUnknown(profile.lighting())).append("\n");
            sb.append("  - 氛围：").append(nullToUnknown(profile.mood())).append("\n");
        } else {
            String mode = referenceMode.toLowerCase();
            if ("style".equals(mode) || "overall".equals(mode)) {
                sb.append("  - 风格：").append(nullToUnknown(profile.style())).append("\n");
                sb.append("  - 氛围：").append(nullToUnknown(profile.mood())).append("\n");
            }
            if ("color".equals(mode) || "overall".equals(mode)) {
                sb.append("  - 色彩：").append(nullToUnknown(profile.colors())).append("\n");
                sb.append("  - 光影：").append(nullToUnknown(profile.lighting())).append("\n");
            }
            if ("composition".equals(mode) || "overall".equals(mode)) {
                sb.append("  - 构图：").append(nullToUnknown(profile.composition())).append("\n");
            }
        }

        return sb.toString();
    }

    private String formatTemplate(StyleTemplate template) {
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

    private String nullToUnknown(String value) {
        return (value == null || value.isBlank()) ? "未分析" : value;
    }

    private String truncateText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        int cutPoint = text.lastIndexOf('\n', maxLen);
        if (cutPoint > 0) {
            return text.substring(0, cutPoint) + "\n...（截断）";
        }
        return text.substring(0, maxLen) + "...";
    }
}
