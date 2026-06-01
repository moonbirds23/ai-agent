package com.zzp.aiagent.rag.model;

import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.enhance.PackedRagContext;
import com.zzp.aiagent.template.model.StyleTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 增强上下文，包含三层增强数据：明确参考图、RAG检索图、风格模板。
 * 提供 fluent builder 风格的 setter 方法，便于链式构建。
 */
public class RagContext {

    private final List<ReferencePicture> explicitReferences = new ArrayList<>();
    private final List<ReferencePicture> retrievedReferences = new ArrayList<>();
    private final Map<String, Object> trace = new LinkedHashMap<>();
    private StyleTemplate styleTemplate;
    private PackedRagContext packedContext;

    /**
     * 参考图组合：图库图片 + 可选的 AI 图片画像。
     */
    public record ReferencePicture(
            GalleryPicture picture,
            PictureAiProfile profile
    ) {}

    public static RagContext empty() {
        return new RagContext();
    }

    public RagContext addExplicit(ReferencePicture ref) {
        if (ref != null) {
            explicitReferences.add(ref);
        }
        return this;
    }

    public RagContext addRetrieved(ReferencePicture ref) {
        if (ref != null) {
            retrievedReferences.add(ref);
        }
        return this;
    }

    public RagContext withTemplate(StyleTemplate template) {
        this.styleTemplate = template;
        return this;
    }

    public boolean isEmpty() {
        return explicitReferences.isEmpty() && retrievedReferences.isEmpty() && styleTemplate == null;
    }

    // ── getters ────────────────────────────────────────────

    public List<ReferencePicture> getExplicitReferences() {
        return explicitReferences;
    }

    public List<ReferencePicture> getRetrievedReferences() {
        return retrievedReferences;
    }

    public StyleTemplate getStyleTemplate() {
        return styleTemplate;
    }

    public Map<String, Object> getTrace() {
        return trace;
    }

    public RagContext putTrace(String key, Object value) {
        if (key != null && value != null) {
            trace.put(key, value);
        }
        return this;
    }

    public RagContext withPacked(PackedRagContext packed) {
        this.packedContext = packed;
        return this;
    }

    public PackedRagContext getPackedContext() {
        return packedContext;
    }
}
