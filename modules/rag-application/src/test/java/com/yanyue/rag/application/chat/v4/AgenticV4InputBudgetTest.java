package com.yanyue.rag.application.chat.v4;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.domain.agent.v4.AgentBudgetLedger;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.RequirementPlan;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.SearchQueryRole;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.OffsetUnit;
import com.yanyue.rag.domain.chunking.v4.SourceAnchor;
import com.yanyue.rag.domain.chunking.v4.SourceAnchorSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgenticV4InputBudgetTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestAnalysisSendsAtMostFourThousandEstimatedTokens() throws Exception {
        var capturedSystem = new AtomicReference<String>();
        var capturedUser = new AtomicReference<String>();
        var invoker = invokerReturning("""
                {"standaloneObjective":"核实部署要求","objectiveRequirements":[{
                  "description":"核实部署要求","mandatory":true,"mappedGoalKeys":["g1"]
                }],"answerConstraints":[],"goals":[{"key":"g1","question":"如何部署",
                  "requirements":[{"key":"r1","description":"部署步骤"}],
                  "initialQuery":{"text":"部署步骤","searchMode":"SEMANTIC"}}]}
                """, capturedSystem, capturedUser);
        var reasoner = new RequestAnalysisReasoner(invoker, objectMapper);
        var messages = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> "会话" + index + "内".repeat(500)).toList();

        reasoner.analyze(UUID.randomUUID(), UUID.randomUUID(), "原问题" + "长".repeat(10_000),
                messages, mock(AgentBudgetLedger.class));

        assertTrue(AgenticV4ModelInvoker.estimatedTokens(capturedSystem.get())
                + AgenticV4ModelInvoker.estimatedTokens(capturedUser.get()) <= 4_000);
        assertTrue(objectMapper.readTree(capturedUser.get()).path("recentMessages").size() <= 6);
    }

    @Test
    void deepReadTrimsLowPrioritySpansToSixThousandEstimatedTokens() throws Exception {
        var capturedSystem = new AtomicReference<String>();
        var capturedUser = new AtomicReference<String>();
        var invoker = invokerReturning("{\"selections\":[]}", capturedSystem, capturedUser);
        var reasoner = new DeepReadReasoner(invoker, objectMapper);
        var goalId = UUID.randomUUID();
        var requirement = new RequirementPlan(UUID.randomUUID(), goalId, "要求" + "细".repeat(180));
        var query = new SearchQuery(UUID.randomUUID(), goalId, ResearchPhase.PRIMARY,
                SearchQueryRole.INITIAL, "部署" + "词".repeat(180), SearchMode.SEMANTIC,
                Set.of(requirement.id()));
        var goal = new GoalPlan(goalId, "目标" + "问".repeat(380), List.of(requirement), query);
        var spans = new ArrayList<CandidateSpan>();
        for (int index = 0; index < 8; index++) spans.add(span("证".repeat(500), index));

        reasoner.select(UUID.randomUUID(), UUID.randomUUID(), "目标" + "景".repeat(780), goal,
                ResearchPhase.PRIMARY, List.of(requirement.id()), List.of(), List.of(query), spans,
                mock(AgentBudgetLedger.class));

        int tokens = AgenticV4ModelInvoker.estimatedTokens(capturedSystem.get())
                + AgenticV4ModelInvoker.estimatedTokens(capturedUser.get());
        int offered = objectMapper.readTree(capturedUser.get()).path("candidateSpans").size();
        assertTrue(tokens <= 6_000);
        assertTrue(offered > 0 && offered < spans.size());
    }

    @SuppressWarnings("unchecked")
    private AgenticV4ModelInvoker invokerReturning(
            String response,
            AtomicReference<String> capturedSystem,
            AtomicReference<String> capturedUser
    ) {
        var invoker = mock(AgenticV4ModelInvoker.class);
        when(invoker.invokeJson(any(), any(), anyString(), anyString(), anyString(), anyString(), anyInt(),
                any(), any(), any())).thenAnswer(call -> {
            capturedSystem.set(call.getArgument(4));
            capturedUser.set(call.getArgument(5));
            var parser = (Function<String, Object>) call.getArgument(9);
            return parser.apply(response);
        });
        return invoker;
    }

    private CandidateSpan span(String text, int index) {
        var versionId = UUID.randomUUID();
        var parentId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var segment = new SourceAnchorSegment(blockId, 0, text.length(), 0, text.length(), index * 1_000,
                index * 1_000 + text.length(), 1);
        var anchor = new SourceAnchor(versionId, parentId, 0, text.length(), OffsetUnit.UTF16_CODE_UNIT,
                OffsetUnit.UTF16_CODE_UNIT, OffsetUnit.UTF16_CODE_UNIT, List.of(segment));
        return new CandidateSpan(UUID.randomUUID().toString(), parentId, 0, text.length(), text,
                List.of("测试文档", "测试章节"), anchor,
                500, index, 8 - index);
    }
}
