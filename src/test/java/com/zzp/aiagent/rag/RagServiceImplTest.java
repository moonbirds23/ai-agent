package com.zzp.aiagent.rag;

import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.domain.rag.RagContext;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.service.RagContextPacker;
import com.zzp.aiagent.service.RagQueryRewriteService;
import com.zzp.aiagent.service.RagReranker;
import com.zzp.aiagent.domain.rag.RagRewriteResult;
import com.zzp.aiagent.domain.rag.RagTrace;
import com.zzp.aiagent.service.RagTraceService;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.service.impl.RagServiceImpl;
import com.zzp.aiagent.domain.template.StyleTemplate;
import com.zzp.aiagent.domain.rag.RagProperties;
import com.zzp.aiagent.service.impl.ExplicitReferenceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 RagServiceImpl 三层增强编排逻辑的正确性：
 * - Layer 1 → Layer 2（增强检索）→ Layer 3 的优先级和决策条件
 * - 各层之间的短路/跳过逻辑
 * - useGalleryRag 关闭时的行为
 * - styleTemplateCode 显式指定时的行为
 *
 * <h3>测试分类</h3>
 * 集成测试（mock 依赖）。所有增强组件全部 mock，只验证编排逻辑。
 */
@DisplayName("RagServiceImpl：三层增强编排")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("integration")
class RagServiceImplTest {

    @Mock private ExplicitReferenceResolver explicitResolver;
    @Mock private StyleTemplateService templateService;
    @Mock private RagProperties ragProperties;
    @Mock private ObjectProvider<RagQueryRewriteService> rewriteProvider;
    @Mock private ObjectProvider<HybridGalleryRetriever> hybridProvider;
    @Mock private ObjectProvider<RagReranker> rerankerProvider;
    @Mock private ObjectProvider<RagContextPacker> packerProvider;
    @Mock private ObjectProvider<RagTraceService> traceProvider;
    @Mock private ObjectProvider<ChatMemory> chatMemoryProvider;

    @Mock private RagQueryRewriteService rewriteService;
    @Mock private HybridGalleryRetriever hybridRetriever;
    @Mock private RagReranker reranker;
    @Mock private RagContextPacker packer;
    @Mock private RagTraceService traceService;

    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        when(rewriteProvider.getIfAvailable()).thenReturn(rewriteService);
        when(hybridProvider.getIfAvailable()).thenReturn(hybridRetriever);
        when(rerankerProvider.getIfAvailable()).thenReturn(reranker);
        when(packerProvider.getIfAvailable()).thenReturn(packer);
        when(traceProvider.getIfAvailable()).thenReturn(traceService);

        // Default: rewrite returns fallback (same as original), retrieve returns empty
        when(rewriteService.rewrite(anyString(), anyString()))
                .thenReturn(RagRewriteResult.fallback("test"));
        when(hybridRetriever.retrieve(any())).thenReturn(Collections.emptyList());
        when(reranker.rerank(any(), any())).thenReturn(Collections.emptyList());

        when(ragProperties.topK()).thenReturn(5);
        when(ragProperties.minScore()).thenReturn(0.4);
        when(ragProperties.retrieveFavoritesOnly()).thenReturn(true);
        when(ragProperties.maxContextChars()).thenReturn(2500);

        ragService = new RagServiceImpl(explicitResolver, templateService,
                ragProperties, rewriteProvider, hybridProvider, rerankerProvider,
                packerProvider, traceProvider, chatMemoryProvider);
    }

    private static GalleryPicture samplePic(long id, String name) {
        return new GalleryPicture(
                id, "http://example.com/pic" + id + ".jpg", null, name, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, false, null, null, "MAIN", null
        );
    }

    private static PictureAiProfile sampleProfile(long id) {
        return new PictureAiProfile(
                id, "主体" + id, "场景", "Q版风格", "暖色",
                "横向", "柔和", "温馨", "prompt...",
                "主体：主体" + id + "\n风格：Q版风格\n", 1, LocalDateTime.now()
        );
    }

    // ── Layer 1: 明确参考图 ────────────────────────────────────────

    @Nested
    @DisplayName("Layer 1：明确参考图（最高优先级）")
    class Layer1Explicit {

        /**
         * 目的：当 request.referencePictureIds 有值时，应调用 ExplicitReferenceResolver 解析。
         */
        @Test
        @DisplayName("指定 referencePictureIds → 调用 explicitResolver")
        void explicitIdsPassed_callsResolver() {
            when(explicitResolver.resolve(anyList())).thenReturn(List.of());

            ChatRequest req = new ChatRequest("测试消息", null, true, null, null,
                    "image_generation", List.of(1L, 2L), null, null, null, null);
            ragService.buildContext(req);

            verify(explicitResolver).resolve(List.of(1L, 2L));
        }

        /**
         * 目的：解析结果应出现在上下文的 explicit 列表中。
         */
        @Test
        @DisplayName("解析成功 → explicit 列表含结果")
        void resolvedSuccessfully_inContext() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(
                    samplePic(1L, "参考图1"), sampleProfile(1L));
            when(explicitResolver.resolve(anyList())).thenReturn(List.of(ref));

            ChatRequest req = new ChatRequest("测试", null, true, null, null,
                    "image_generation", List.of(1L), null, null, null, null);
            RagContext ctx = ragService.buildContext(req);

            assertThat(ctx.getExplicitReferences()).hasSize(1);
            assertThat(ctx.getExplicitReferences().get(0).picture().name()).isEqualTo("参考图1");
        }
    }

    // ── Layer 2: RAG 增强检索 ──────────────────────────────────────

    @Nested
    @DisplayName("Layer 2：RAG 增强检索")
    class Layer2Rag {

        /**
         * 目的：useGalleryRag 未设置（null）时，默认启用增强检索。
         */
        @Test
        @DisplayName("useGalleryRag 未设置(null) → 默认启用检索")
        void useGalleryRagNull_defaultsToEnabled() {
            ChatRequest req = new ChatRequest("卡通风格", null, true, null, null,
                    "image_generation", null, null, null, null, null);
            ragService.buildContext(req);

            verify(rewriteService).rewrite("卡通风格", "");
            verify(hybridRetriever).retrieve(any());
        }

        /**
         * 目的：useGalleryRag=true 时启用检索。
         */
        @Test
        @DisplayName("useGalleryRag=true → 启用检索")
        void useGalleryRagTrue_enablesRetrieval() {
            ChatRequest req = new ChatRequest("卡通风格", null, true, null, null,
                    "image_generation", null, true, null, null, null);
            ragService.buildContext(req);

            verify(rewriteService).rewrite("卡通风格", "");
            verify(hybridRetriever).retrieve(any());
        }

        /**
         * 目的：useGalleryRag=false 时跳过检索。
         */
        @Test
        @DisplayName("useGalleryRag=false → 跳过检索")
        void useGalleryRagFalse_skipsRetrieval() {
            ChatRequest req = new ChatRequest("卡通风格", null, true, null, null,
                    "image_generation", null, false, null, null, null);
            ragService.buildContext(req);

            verify(rewriteService, never()).rewrite(anyString(), anyString());
            verify(hybridRetriever, never()).retrieve(any());
        }

        /**
         * 目的：检索结果应出现在 retrieved 列表中。
         */
        @Test
        @DisplayName("检索到结果 → retrieved 列表含结果")
        void retrievedResults_inContext() {
            GalleryPicture pic = samplePic(1L, "检索图1");
            PictureAiProfile profile = sampleProfile(1L);
            RagCandidate candidate = new RagCandidate(pic, profile,
                    0.7, 10, 15, 0.0, List.of("语义高度匹配"));
            when(reranker.rerank(any(), any())).thenReturn(List.of(candidate));

            ChatRequest req = new ChatRequest("卡通风格", null, true, null, null,
                    "image_generation", null, true, null, null, null);
            RagContext ctx = ragService.buildContext(req);

            assertThat(ctx.getRetrievedReferences()).hasSize(1);
            assertThat(ctx.getRetrievedReferences().get(0).picture().name()).isEqualTo("检索图1");
        }

        /**
         * 目的：message 为空时不应调用检索。
         */
        @Test
        @DisplayName("空消息 → 不调用检索")
        void blankMessage_skipsRetrieval() {
            ChatRequest req = new ChatRequest("", null, true, null, null,
                    "image_generation", null, true, null, null, null);
            ragService.buildContext(req);

            verify(rewriteService, never()).rewrite(anyString(), anyString());
            verify(hybridRetriever, never()).retrieve(any());
        }
    }

    // ── Layer 3: 风格模板 ──────────────────────────────────────────

    @Nested
    @DisplayName("Layer 3：风格模板兜底")
    class Layer3Template {

        /**
         * 目的：L1/L2 均为空时才触发 L3 模板匹配。
         */
        @Test
        @DisplayName("L1+L2 都为空 → 触发模板匹配")
        void bothEmpty_triggersTemplate() {
            when(templateService.match(anyString())).thenReturn(Optional.empty());

            ChatRequest req = new ChatRequest("PPT汇报", null, true, null, null,
                    "image_generation", null, null, null, null, null);
            ragService.buildContext(req);

            verify(templateService).match("PPT汇报");
        }

        /**
         * 目的：L1 有数据 → 跳过 L3，不匹配模板。
         */
        @Test
        @DisplayName("L1 有数据 → 不触发模板匹配")
        void layer1Filled_skipsTemplate() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(
                    samplePic(1L, "pic1"), sampleProfile(1L));
            when(explicitResolver.resolve(anyList())).thenReturn(List.of(ref));

            ChatRequest req = new ChatRequest("PPT汇报", null, true, null, null,
                    "image_generation", List.of(1L), null, null, null, null);
            ragService.buildContext(req);

            verify(templateService, never()).match(anyString());
        }

        /**
         * 目的：L2 有数据 → 跳过 L3。
         */
        @Test
        @DisplayName("L2 有数据 → 不触发模板匹配")
        void layer2Filled_skipsTemplate() {
            GalleryPicture pic = samplePic(1L, "pic1");
            RagCandidate candidate = new RagCandidate(pic, sampleProfile(1L),
                    0.7, 10, 15, 0.0, List.of());
            when(reranker.rerank(any(), any())).thenReturn(List.of(candidate));

            ChatRequest req = new ChatRequest("PPT汇报", null, true, null, null,
                    "image_generation", null, true, null, null, null);
            ragService.buildContext(req);

            verify(templateService, never()).match(anyString());
        }

        /**
         * 目的：模板匹配成功 → 上下文含 StyleTemplate。
         */
        @Test
        @DisplayName("模板匹配成功 → 模板出现在上下文中")
        void templateMatched_inContext() {
            StyleTemplate tmpl = new StyleTemplate("ppt-business-flat", "PPT商务扁平",
                    "work_study", "ppt", List.of("PPT"), "prompt", null, "16:9");
            when(templateService.match(anyString())).thenReturn(Optional.of(tmpl));

            ChatRequest req = new ChatRequest("PPT汇报", null, true, null, null,
                    "image_generation", null, true, null, null, null);
            RagContext ctx = ragService.buildContext(req);

            assertThat(ctx.getStyleTemplate()).isNotNull();
            assertThat(ctx.getStyleTemplate().code()).isEqualTo("ppt-business-flat");
        }

        /**
         * 目的：显式指定 styleTemplateCode 时，用 getByCode 查找而非 match。
         */
        @Test
        @DisplayName("指定 styleTemplateCode → 用 getByCode 精确查找")
        void explicitCode_usesGetByCode() {
            StyleTemplate tmpl = new StyleTemplate("custom-code", "自定义", null,
                    null, null, null, null, null);
            when(templateService.getByCode("custom-code")).thenReturn(Optional.of(tmpl));

            ChatRequest req = new ChatRequest("测试", null, true, null, null,
                    "image_generation", null, true, null, "custom-code", null);
            RagContext ctx = ragService.buildContext(req);

            assertThat(ctx.getStyleTemplate()).isNotNull();
            assertThat(ctx.getStyleTemplate().code()).isEqualTo("custom-code");
            verify(templateService, never()).match(anyString());
        }
    }

    // ── 组合场景 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("组合场景：多层同时存在")
    class CombinedScenarios {

        /**
         * 目的：L1+L2 同时有数据时，L3 不触发（因 L1 有数据）。
         */
        @Test
        @DisplayName("L1 存在 → L2 仍然检索，L3 短路")
        void layer1And2_active_layer3_skipped() {
            RagContext.ReferencePicture ref1 = new RagContext.ReferencePicture(
                    samplePic(1L, "明确图"), sampleProfile(1L));
            when(explicitResolver.resolve(anyList())).thenReturn(List.of(ref1));

            GalleryPicture pic2 = samplePic(2L, "检索图");
            RagCandidate candidate = new RagCandidate(pic2, sampleProfile(2L),
                    0.7, 10, 15, 0.0, List.of());
            when(reranker.rerank(any(), any())).thenReturn(List.of(candidate));

            ChatRequest req = new ChatRequest("卡通", null, true, null, null,
                    "image_generation", List.of(1L), true, null, null, null);
            RagContext ctx = ragService.buildContext(req);

            assertThat(ctx.getExplicitReferences()).hasSize(1);
            assertThat(ctx.getRetrievedReferences()).hasSize(1);
            assertThat(ctx.getStyleTemplate()).isNull(); // L3 被短路
            verify(templateService, never()).match(anyString());
        }

        /**
         * 目的：Request message 为 null 时，L2 和 L3 都不触发。
         */
        @Test
        @DisplayName("message=null → L2+L3 均跳过")
        void nullMessage_skipsLayer2And3() {
            ChatRequest req = new ChatRequest(null, null, true, null, null,
                    "image_generation", null, true, null, null, null);
            ragService.buildContext(req);

            verify(rewriteService, never()).rewrite(anyString(), anyString());
            verify(hybridRetriever, never()).retrieve(any());
            verify(templateService, never()).match(anyString());
        }
    }
}
