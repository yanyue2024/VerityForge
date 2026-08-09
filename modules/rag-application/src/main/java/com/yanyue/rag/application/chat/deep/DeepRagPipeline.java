package com.yanyue.rag.application.chat.deep;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.chat.ConversationalAnswerService;
import com.yanyue.rag.application.chat.KnowledgeDemandClassifier;
import com.yanyue.rag.application.chat.RunEventHub;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.agent.deep.DeepRagLimits;
import com.yanyue.rag.domain.agent.deep.GoalEvidencePool;
import com.yanyue.rag.domain.agent.deep.RequestAnalysis;
import com.yanyue.rag.domain.agent.deep.DeepRagProfiles;
import com.yanyue.rag.domain.agent.deep.DeepReadEvidenceStrategy;
import com.yanyue.rag.domain.port.DeepRunArtifactPort;
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

/** Final Deep RAG pipeline validated by the 200-case Chinese enterprise benchmark. */
@Service
public final class DeepRagPipeline extends DeepRagStateMachine {
    public static final String PIPELINE_VERSION = "deep-rag-final";
    private final RunRecordPort deepRunRecords;
    private final DeepRagProperties properties;
    private final Executor deepExecutor;

    public DeepRagPipeline(
            PipelineConfigService pipelineConfigs,
            MetadataSchemaService metadataSchemas,
            ConversationMemoryPort memory,
            RequestAnalyzer requestAnalysis,
            GoalResearchService research,
            EvidenceJudge judge,
            CoverageStateReducer coverageReducer,
            GapActionReducer gapActions,
            DeepAnswerService answerService,
            ConversationalAnswerService conversationalAnswers,
            KnowledgeDemandClassifier knowledgeDemand,
            AssistantProfileService assistantProfiles,
            DeepRunArtifactPort artifacts,
            RunRecordPort runRecords,
            RunEventHub events,
            @Qualifier("ragRunExecutor") Executor executor,
            Clock clock,
            ObjectMapper objectMapper,
            DeepRagProperties properties
    ) {
        super(pipelineConfigs, metadataSchemas, memory, requestAnalysis, research, judge, coverageReducer,
                gapActions, answerService, conversationalAnswers, knowledgeDemand, assistantProfiles,
                artifacts, runRecords, events, executor, clock, objectMapper);
        this.deepRunRecords = runRecords;
        this.properties = properties;
        this.deepExecutor = executor;
    }

    @Override
    protected String pipelineVersion() {
        return PIPELINE_VERSION;
    }

    @Override
    protected String limitsVersion() {
        return DeepRagProfiles.VERSION + ":" + properties.strategy().name();
    }

    @Override
    protected DeepRagLimits limits() {
        return properties.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                ? DeepRagProfiles.finalProfile()
                : DeepRagProfiles.defaults(properties.strategy());
    }

    @Override
    protected void applyRuntime(UUID runId, com.yanyue.rag.domain.model.PipelineConfig config,
                                UUID profileId, DeepRagLimits limits) {
        var effectiveLimits = new LinkedHashMap<>(asMap(limits));
        effectiveLimits.put("profileName", properties.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                ? DeepRagProfiles.FINAL_PROFILE : "DEEP_EXPERIMENTAL_" + properties.strategy().name());
        effectiveLimits.put("evidenceLimits", Map.of(
                "acceptedTotal", limits.acceptedEvidenceLimit(),
                "perGoal", limits.evidencePerGoalLimit(),
                "perRequirement", limits.evidencePerRequirementLimit(),
                "perParentAndPhase", limits.evidencePerParentAndPhaseLimit(),
                "finalAnswerReferences", limits.finalAnswerReferenceLimit()));
        effectiveLimits.put("finalAnswerPacking", Map.of(
                "strategy", properties.strategy() == DeepReadEvidenceStrategy.GOAL_BATCHED_PARENT
                        ? "UNIQUE_PARENT_GOAL_COVERAGE_TOKEN_BUDGET" : "LEGACY_REFERENCE_LIMIT",
                "inputTokenBudget", limits.tokens().finalAnswerInput(),
                "outputTokenBudget", limits.tokens().finalAnswerOutput(),
                "referenceSafetyCeiling", limits.finalAnswerReferenceLimit()));
        effectiveLimits.put("operationLimits", Map.ofEntries(
                Map.entry("goals", DeepRagLimits.MAX_GOALS),
                Map.entry("primaryQueriesPerGoal", DeepRagLimits.MAX_PRIMARY_QUERIES_PER_GOAL),
                Map.entry("repairQueriesPerGoal", DeepRagLimits.MAX_REPAIR_QUERIES_PER_GOAL),
                Map.entry("physicalSearches", limits.maximum(BudgetDimension.PHYSICAL_SEARCH)),
                Map.entry("primaryDeepReads", limits.maximum(BudgetDimension.PRIMARY_DEEP_READ_CALL)),
                Map.entry("repairDeepReads", limits.maximum(BudgetDimension.REPAIR_DEEP_READ_CALL)),
                Map.entry("evidenceJudges", limits.maximum(BudgetDimension.EVIDENCE_JUDGE_CALL)),
                Map.entry("logicalModelCalls", limits.maximum(BudgetDimension.GENERATIVE_LLM_LOGICAL_CALL)),
                Map.entry("physicalModelAttempts", limits.maximum(
                        BudgetDimension.GENERATIVE_LLM_PHYSICAL_ATTEMPT)),
                Map.entry("structuredModelOutputCeilingTokens",
                        DeepRagProfiles.STRUCTURED_MODEL_OUTPUT_CEILING_TOKENS),
                Map.entry("batchedParentDeepReadOutputTokens",
                        DeepRagProfiles.BATCHED_PARENT_DEEP_READ_OUTPUT_TOKENS)));
        deepRunRecords.applyDeepRuntime(runId, config, profileId, Map.copyOf(effectiveLimits),
                properties.runtimePromptVersion());
    }

    @Override
    protected RequestAnalysis analyzeRequest(
            UUID profileId,
            UUID runId,
            String question,
            List<String> recentMessages,
            com.yanyue.rag.domain.agent.budget.AgentBudgetLedger ledger,
            DeepRagLimits limits
    ) {
        return requestAnalysis().analyze(profileId, runId, question, recentMessages, ledger, limits);
    }

    @Override
    protected EvidenceJudge.JudgeDecision judgeEvidence(
            UUID profileId,
            UUID runId,
            RequestAnalysis analysis,
            GoalEvidencePool pool,
            com.yanyue.rag.domain.agent.budget.AgentBudgetLedger ledger,
            DeepRagLimits limits
    ) {
        if (properties.strategy().batchesParentsByGoal()) {
            var futures = analysis.goals().stream().map(goal ->
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> judgeReasoner().judgeGoal(profileId, runId,
                                    analysis.standaloneObjective(), goal, pool, ledger, limits),
                            deepExecutor)).toList();
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
            var decisions = new ArrayList<EvidenceJudge.GoalDecision>();
            boolean degraded = false;
            for (var future : futures) {
                var result = future.join();
                decisions.addAll(result.goals());
                degraded |= result.degraded();
            }
            return new EvidenceJudge.JudgeDecision(decisions, degraded);
        }
        return judgeReasoner().judge(profileId, runId, analysis, pool, ledger, limits);
    }
}
