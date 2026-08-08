package com.yanyue.rag.application.chat.v8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.chat.v5.AgentAnswerServiceV5;
import com.yanyue.rag.application.chat.v5.CoverageStateReducerV5;
import com.yanyue.rag.application.chat.v5.EvidenceJudgeReasonerV5;
import com.yanyue.rag.application.chat.v5.RequestAnalysisReasonerV5;
import com.yanyue.rag.application.chat.v7.AgenticRagV7Pipeline;
import com.yanyue.rag.application.chat.v7.GapActionReducerV7;
import com.yanyue.rag.application.chat.v7.GoalResearchServiceV7;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.domain.agent.v4.BudgetDimension;
import com.yanyue.rag.domain.agent.v5.AgenticV5Limits;
import com.yanyue.rag.domain.agent.v5.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v5.RequestAnalysis;
import com.yanyue.rag.domain.agent.v8.AgenticV8Limits;
import com.yanyue.rag.domain.agent.v8.DeepReadEvidenceStrategy;
import com.yanyue.rag.domain.port.AgenticV4ArtifactPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.RunRecordPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * v8 facade. It intentionally reuses the bounded v7 state machine and evidence
 * contract, while selecting a wider retrieval funnel and writing an explicit
 * v8 runtime snapshot so v7 and v8 evaluations remain distinguishable.
 */
@Service
public class AgenticRagV8Pipeline extends AgenticRagV7Pipeline {
    public static final String PIPELINE_VERSION = "agentic-rag-v8";
    private final RunRecordPort v8RunRecords;
    private final DeepReadStrategyProperties deepReadStrategy;
    private final Executor v8Executor;

    public AgenticRagV8Pipeline(
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            ConversationMemoryPort memory,
            RequestAnalysisReasonerV5 requestAnalysis,
            GoalResearchServiceV7 research,
            EvidenceJudgeReasonerV5 judge,
            CoverageStateReducerV5 coverageReducer,
            GapActionReducerV7 gapActions,
            AgentAnswerServiceV5 answerService,
            ConversationalAnswerService conversationalAnswers,
            KnowledgeDemandClassifier knowledgeDemand,
            AssistantProfileService assistantProfiles,
            AgenticV4ArtifactPort artifacts,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            ObjectMapper objectMapper,
            DeepReadStrategyProperties deepReadStrategy
    ) {
        super(pipelineConfigs, metadataSchemas, memory, requestAnalysis, research, judge, coverageReducer,
                gapActions, answerService, conversationalAnswers, knowledgeDemand, assistantProfiles,
                artifacts, runRecords, events, executor, clock, objectMapper);
        this.v8RunRecords = runRecords;
        this.deepReadStrategy = deepReadStrategy;
        this.v8Executor = executor;
    }

    @Override
    protected String pipelineVersion() {
        return PIPELINE_VERSION;
    }

    @Override
    protected String limitsVersion() {
        return AgenticV8Limits.VERSION + ":" + deepReadStrategy.strategy().name();
    }

    @Override
    protected AgenticV5Limits limits() {
        return deepReadStrategy.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                ? AgenticV8Limits.finalProfile()
                : AgenticV8Limits.defaults(deepReadStrategy.strategy());
    }

    @Override
    protected void applyRuntime(UUID runId, com.yanyue.rag.domain.model.PipelineConfig config,
                                UUID profileId, AgenticV5Limits limits) {
        var effectiveLimits = new LinkedHashMap<>(asMap(limits));
        effectiveLimits.put("profileName", deepReadStrategy.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                ? AgenticV8Limits.FINAL_PROFILE : "V8_EXPERIMENTAL_" + deepReadStrategy.strategy().name());
        effectiveLimits.put("evidenceLimits", Map.of(
                "acceptedTotal", limits.acceptedEvidenceLimit(),
                "perGoal", limits.evidencePerGoalLimit(),
                "perRequirement", limits.evidencePerRequirementLimit(),
                "perParentAndPhase", limits.evidencePerParentAndPhaseLimit(),
                "finalAnswerReferences", limits.finalAnswerReferenceLimit()));
        effectiveLimits.put("finalAnswerPacking", Map.of(
                "strategy", deepReadStrategy.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                        ? "UNIQUE_PARENT_GOAL_COVERAGE_TOKEN_BUDGET" : "LEGACY_REFERENCE_LIMIT",
                "inputTokenBudget", limits.tokens().finalAnswerInput(),
                "outputTokenBudget", limits.tokens().finalAnswerOutput(),
                "referenceSafetyCeiling", limits.finalAnswerReferenceLimit()));
        effectiveLimits.put("operationLimits", Map.ofEntries(
                Map.entry("goals", AgenticV5Limits.MAX_GOALS),
                Map.entry("primaryQueriesPerGoal", AgenticV5Limits.MAX_PRIMARY_QUERIES_PER_GOAL),
                Map.entry("repairQueriesPerGoal", AgenticV5Limits.MAX_REPAIR_QUERIES_PER_GOAL),
                Map.entry("physicalSearches", limits.maximum(BudgetDimension.PHYSICAL_SEARCH)),
                Map.entry("primaryDeepReads", limits.maximum(BudgetDimension.PRIMARY_DEEP_READ_CALL)),
                Map.entry("repairDeepReads", limits.maximum(BudgetDimension.REPAIR_DEEP_READ_CALL)),
                Map.entry("evidenceJudges", limits.maximum(BudgetDimension.EVIDENCE_JUDGE_CALL)),
                Map.entry("logicalModelCalls", limits.maximum(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL)),
                Map.entry("physicalModelAttempts", limits.maximum(
                        BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT)),
                Map.entry("structuredModelOutputCeilingTokens",
                        AgenticV8Limits.STRUCTURED_MODEL_OUTPUT_CEILING_TOKENS),
                Map.entry("batchedParentDeepReadOutputTokens",
                        AgenticV8Limits.BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS)));
        v8RunRecords.applyAgentV8Runtime(runId, config, profileId, Map.copyOf(effectiveLimits),
                deepReadStrategy.runtimePromptVersion());
    }

    @Override
    protected RequestAnalysis analyzeRequest(
            UUID profileId,
            UUID runId,
            String question,
            List<String> recentMessages,
            com.yanyue.rag.domain.agent.v4.AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        return requestAnalysis().analyzeV8(profileId, runId, question, recentMessages, ledger, limits);
    }

    @Override
    protected EvidenceJudgeReasonerV5.JudgeDecision judgeEvidence(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            com.yanyue.rag.domain.agent.v4.AgentBudgetLedger ledger,
            AgenticV5Limits limits
    ) {
        if (deepReadStrategy.strategy().batchesParentsByGoal()) {
            var futures = analysis.goals().stream().map(goal ->
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> judgeReasoner().judgeGoalV8(profileId, runId,
                                    analysis.standaloneObjective(), goal, pool, ledger, limits),
                            v8Executor)).toList();
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            var decisions = new ArrayList<EvidenceJudgeReasonerV5.GoalDecision>();
            boolean degraded = false;
            for (var future : futures) {
                var result = future.join();
                decisions.addAll(result.goals());
                degraded |= result.degraded();
            }
            return new EvidenceJudgeReasonerV5.JudgeDecision(decisions, degraded);
        }
        return judgeReasoner().judgeV8(profileId, runId, analysis, pool, ledger, limits);
    }
}
