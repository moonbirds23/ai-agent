package com.zzp.aiagent.rag;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.template.StyleTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 三层增强服务实现。
 *
 * <pre>
 * Layer 1: 明确参考图 — 用户显式指定 pictureId，优先级最高
 * Layer 2: RAG 检索 — 对用户需求做语义检索，召回历史收藏图
 * Layer 3: 风格模板 — 无参考图时，匹配系统预设的风格模板作为降级兜底
 * </pre>
 */
@Component
@Profile("!test")
@Slf4j
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final ExplicitReferenceResolver explicitResolver;
    private final GalleryRagRetriever ragRetriever;
    private final StyleTemplateService templateService;

    @Override
    public RagContext buildContext(ChatRequest request) {
        RagContext ctx = RagContext.empty();

        // Layer 1: 明确参考图（最高优先级）
        if (request.referencePictureIds() != null && !request.referencePictureIds().isEmpty()) {
            List<RagContext.ReferencePicture> refs = explicitResolver.resolve(request.referencePictureIds());
            for (RagContext.ReferencePicture ref : refs) {
                ctx.addExplicit(ref);
            }
        }

        // Layer 2: RAG 检索历史收藏图（useGalleryRag 默认为 true）
        if (request.useGalleryRag() == null || request.useGalleryRag()) {
            String query = request.message();
            if (query != null && !query.isBlank()) {
                List<RagContext.ReferencePicture> retrieved = ragRetriever.retrieve(query);
                for (RagContext.ReferencePicture ref : retrieved) {
                    ctx.addRetrieved(ref);
                }
            }
        }

        // Layer 3: 风格模板降级兜底（仅在前两层均无结果时生效）
        if (ctx.getExplicitReferences().isEmpty() && ctx.getRetrievedReferences().isEmpty()) {
            String query = request.message();
            if (query != null && !query.isBlank()) {
                if (request.styleTemplateCode() != null && !request.styleTemplateCode().isBlank()) {
                    templateService.getByCode(request.styleTemplateCode())
                            .ifPresent(ctx::withTemplate);
                } else {
                    templateService.match(query).ifPresent(ctx::withTemplate);
                }
            }
        }

        log.info("[RagService] 上下文构建完成: explicit={}, retrieved={}, template={}",
                ctx.getExplicitReferences().size(),
                ctx.getRetrievedReferences().size(),
                ctx.getStyleTemplate() != null ? ctx.getStyleTemplate().code() : "none");
        return ctx;
    }
}
