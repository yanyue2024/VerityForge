package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoModeRouterTest {
    private final AutoModeRouter router = new AutoModeRouter();

    @Test
    void routesExplicitSingleDocumentLookupToFast() {
        assertDecision("根据《Pod 的生命周期》，该部分给出的关键信息是什么？",
                RunMode.FAST, "auto-fast-explicit-document");
        assertDecision("在《管理设备》的“概述”部分，文档具体说明了什么要求或做法？",
                RunMode.FAST, "auto-fast-explicit-document");
    }

    @Test
    void routesSimpleAvailabilityLookupToFast() {
        assertDecision("当前资料是否包含购买专有 Kubernetes 发行版许可证的报价？",
                RunMode.FAST, "auto-fast-simple-lookup");
    }

    @Test
    void routesStableTechnicalAnchorToFast() {
        assertDecision("团队不记得资料名，只知道要确认 ResourceClaimSpec 的作用。",
                RunMode.FAST, "auto-fast-technical-anchor");
        assertDecision("需要完成 Webhook 设计的重要性，应找出哪些具体要求？",
                RunMode.FAST, "auto-fast-technical-anchor");
    }

    @Test
    void deepSignalsVetoEnglishTechnicalAnchors() {
        assertDecision("综合《Kubernetes API 概念》与《ResourceClaim》，分别比较两者的约束。",
                RunMode.DEEP, "auto-deep-multi-document");
        assertDecision("同时处理 Kubernetes 部署和 Webhook 故障两个独立目标，并分别回答。",
                RunMode.DEEP, "auto-deep-multi-goal");
        assertDecision("制定三阶段方案，第一阶段部署，第二阶段配置，第三阶段恢复。",
                RunMode.DEEP, "auto-deep-staged-task");
    }

    @Test
    void routesComparisonsConflictsAndCombinedDiagnosisToDeep() {
        assertDecision("比较两种部署方式的差异和优缺点。",
                RunMode.DEEP, "auto-deep-comparison");
        assertDecision("这些资料是否存在相互冲突，需要交叉验证。",
                RunMode.DEEP, "auto-deep-conflict");
        assertDecision("分析服务失败的根因并给出修复措施。",
                RunMode.DEEP, "auto-deep-diagnose-repair");
    }

    @Test
    void uncertainAndEmptyQuestionsFailDeep() {
        assertDecision("这个系统问题怎么处理？", RunMode.DEEP, "auto-deep-uncertain");
        assertDecision("   ", RunMode.DEEP, "auto-deep-empty");
        assertDecision(null, RunMode.DEEP, "auto-deep-empty");
    }

    @Test
    void retrievalAware50IsDefaultAndAllowsOneTitleHitForMultiGoalQuestions() {
        var query = "同时处理 Kubernetes Pod 与 Webhook 两个独立目标，并分别回答。";

        var decision = router.route(query, List.of(hit("Kubernetes Pod"), hit("无关文档")));

        assertEquals(AutoModeRouter.Profile.RETRIEVAL_AWARE_50, decision.profile());
        assertEquals(RunMode.FAST, decision.mode());
        assertEquals("auto-fast-retrieval-aware-50", decision.reasonCode());
        assertEquals(1, decision.titleHitCount());
    }

    @Test
    void retrievalAware28RequiresTwoTitleHitsForMultiGoalQuestions() {
        var router28 = new AutoModeRouter(AutoModeRouter.Profile.RETRIEVAL_AWARE_28);
        var query = "同时处理 Kubernetes Pod 与 Webhook 两个独立目标，并分别回答。";

        var oneHit = router28.route(query, List.of(hit("Kubernetes Pod"), hit("无关文档")));
        var twoHits = router28.route(query, List.of(hit("Kubernetes Pod"), hit("Webhook")));

        assertEquals(RunMode.DEEP, oneHit.mode());
        assertEquals("auto-deep-retrieval-confidence", oneHit.reasonCode());
        assertEquals(1, oneHit.titleHitCount());
        assertEquals(RunMode.FAST, twoHits.mode());
        assertEquals("auto-fast-retrieval-aware-28", twoHits.reasonCode());
        assertEquals(2, twoHits.titleHitCount());
    }

    @Test
    void stagedQuestionsRequireTwoTitleHitsInBothProfiles() {
        var query = "第一阶段处理 Kubernetes Pod，第二阶段处理 Webhook。";
        var candidates = List.of(hit("Kubernetes Pod"));

        assertEquals(RunMode.DEEP, router.route(query, candidates).mode());
        assertEquals(RunMode.DEEP, new AutoModeRouter(AutoModeRouter.Profile.RETRIEVAL_AWARE_28)
                .route(query, candidates).mode());
    }

    @Test
    void profileCanBeSelectedByConfigurationValueAndRejectsUnknownProfiles() {
        assertEquals(AutoModeRouter.Profile.RETRIEVAL_AWARE_28,
                new AutoModeRouter("retrieval-aware-28").profile());
        assertEquals(AutoModeRouter.Profile.RETRIEVAL_AWARE_50,
                new AutoModeRouter("RETRIEVAL_AWARE_50").profile());
        assertThrows(IllegalArgumentException.class, () -> new AutoModeRouter("unknown"));
    }

    @Test
    void titleHitsAreDistinctAndLimitedToTheFusedTopFive() {
        var query = "同时处理 Kubernetes Pod 与 Webhook 两个独立目标，并分别回答。";
        var candidates = List.of(
                hit("Kubernetes Pod"), hit("Kubernetes Pod"), hit("无关一"), hit("无关二"), hit("无关三"),
                hit("Webhook"));

        var decision = new AutoModeRouter(AutoModeRouter.Profile.RETRIEVAL_AWARE_28)
                .route(query, candidates);

        assertEquals(RunMode.DEEP, decision.mode());
        assertEquals(1, decision.titleHitCount());
    }

    @Test
    void comparisonWithoutMultiGoalSignalRemainsDeepDespiteRetrievalHits() {
        var query = "比较 Kubernetes Pod 与 Webhook 的差异并检查冲突。";

        var decision = router.route(query, List.of(hit("Kubernetes Pod"), hit("Webhook")));

        assertEquals(RunMode.DEEP, decision.mode());
        assertEquals("auto-deep-comparison", decision.reasonCode());
        assertEquals(-1, decision.titleHitCount());
    }

    private void assertDecision(String query, RunMode mode, String reason) {
        var decision = router.route(query);
        assertEquals(mode, decision.mode());
        assertEquals(reason, decision.reasonCode());
    }

    private RetrievalHit hit(String title) {
        return new RetrievalHit(UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                title, "context", 1.0, List.of("rrf"));
    }
}
