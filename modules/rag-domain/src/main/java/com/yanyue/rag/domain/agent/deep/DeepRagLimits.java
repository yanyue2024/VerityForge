package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.agent.AgentBudgetLimits;
import com.yanyue.rag.domain.agent.budget.BudgetDimension;
import com.yanyue.rag.domain.agent.deep.DeepReadEvidenceStrategy;
import java.time.Duration;

public record DeepRagLimits(
        Concurrency concurrency,
        Retrieval retrieval,
        Tokens tokens,
        Duration runDeadline,
        boolean expandedProfile,
        DeepReadEvidenceStrategy deepReadEvidenceStrategy
) implements AgentBudgetLimits {
    public static final String VERSION = "deep-rag-limits-v1";
    public static final int MAX_GOALS = 3;
    public static final int MAX_OBJECTIVE_REQUIREMENTS = 3;
    public static final int MAX_REQUIREMENTS_PER_GOAL = 3;
    public static final int MAX_PRIMARY_QUERIES_PER_GOAL = 2;
    public static final int MAX_PRIMARY_QUERIES = 6;
    public static final int MAX_REPAIR_QUERIES_PER_GOAL = 2;
    public static final int MAX_REPAIR_QUERIES = 6;
    public static final int MAX_PHYSICAL_SEARCHES = 12;
    public static final int MAX_SEMANTIC_EMBEDDINGS = 6;
    public static final int MAX_RERANK_CALLS = 6;
    public static final int MAX_PRIMARY_DEEP_READ_CALLS = 3;
    public static final int MAX_REPAIR_DEEP_READ_CALLS = 3;
    public static final int MAX_EXPANDED_REPAIR_DEEP_READ_CALLS = 6;
    public static final int MAX_DEEP_READ_CALLS = 6;
    public static final int MAX_EVIDENCE_JUDGE_CALLS = 1;
    public static final int MAX_FINAL_ANSWER_CALLS = 1;
    public static final int MAX_GENERATIVE_LLM_LOGICAL_CALLS = 9;
    public static final int MAX_EXPANDED_GENERATIVE_LLM_LOGICAL_CALLS = 12;
    public static final int MAX_GENERATIVE_LLM_PHYSICAL_ATTEMPTS = 12;
    public static final int MAX_EXPANDED_GENERATIVE_LLM_PHYSICAL_ATTEMPTS = 16;
    public static final int MAX_SERIAL_SEMANTIC_STAGES = 5;
    public static final int MAX_PARENT_READS = 24;
    public static final int MAX_CANDIDATE_SPANS_OFFERED = 48;
    public static final int MAX_ACCEPTED_EVIDENCE = 18;
    public static final int MAX_FINAL_REFERENCES = 8;
    public static final int MAX_EVIDENCE_PER_GOAL = 6;
    public static final int MAX_EVIDENCE_PER_REQUIREMENT = 2;
    public static final int MAX_EVIDENCE_PER_PARENT_AND_PHASE = 2;

    public DeepRagLimits {
        DeepValidation.required(concurrency, "concurrency");
        DeepValidation.required(retrieval, "retrieval");
        DeepValidation.required(tokens, "tokens");
        DeepValidation.required(runDeadline, "runDeadline");
        deepReadEvidenceStrategy = deepReadEvidenceStrategy == null
                ? DeepReadEvidenceStrategy.CANDIDATE_SPAN : deepReadEvidenceStrategy;
        var maximumDeadline = expandedProfile ? Duration.ofSeconds(240) : Duration.ofSeconds(120);
        if (runDeadline.isZero() || runDeadline.isNegative() || runDeadline.compareTo(maximumDeadline) > 0) {
            throw new IllegalArgumentException("runDeadline exceeds the profile boundary");
        }
    }

    public DeepRagLimits(
            Concurrency concurrency,
            Retrieval retrieval,
            Tokens tokens,
            Duration runDeadline,
            boolean expandedProfile
    ) {
        this(concurrency, retrieval, tokens, runDeadline, expandedProfile,
                DeepReadEvidenceStrategy.CANDIDATE_SPAN);
    }

    public DeepRagLimits(
            Concurrency concurrency,
            Retrieval retrieval,
            Tokens tokens,
            Duration runDeadline
    ) {
        this(concurrency, retrieval, tokens, runDeadline, false,
                DeepReadEvidenceStrategy.CANDIDATE_SPAN);
    }

    public static DeepRagLimits defaults() {
        return new DeepRagLimits(Concurrency.defaults(), Retrieval.defaults(), Tokens.defaults(),
                Duration.ofSeconds(120));
    }

    public boolean expandedEvidenceProfile() {
        return retrieval.candidateSpanLimit() > 8
                || retrieval.candidateSpanTokens() > 600
                || tokens.deepReadInput() > 6_000;
    }

    public int acceptedEvidenceLimit() {
        return expandedEvidenceProfile() ? 36 : MAX_ACCEPTED_EVIDENCE;
    }

    public int evidencePerGoalLimit() {
        return expandedEvidenceProfile() ? 18 : MAX_EVIDENCE_PER_GOAL;
    }

    public int evidencePerRequirementLimit() {
        return expandedEvidenceProfile() ? 6 : MAX_EVIDENCE_PER_REQUIREMENT;
    }

    public int evidencePerParentAndPhaseLimit() {
        return expandedEvidenceProfile() ? 3 : MAX_EVIDENCE_PER_PARENT_AND_PHASE;
    }

    /** Goal-batched reads pack unique parents by token budget; this remains a safety ceiling. */
    public int finalAnswerReferenceLimit() {
        return deepReadEvidenceStrategy.batchesParentsByGoal()
                ? acceptedEvidenceLimit() : MAX_FINAL_REFERENCES;
    }

    @Override
    public long maximum(BudgetDimension dimension) {
        long parentDeepReadCalls = (long) MAX_GOALS * retrieval.parentLimitPerGoalPhase();
        long batchedDeepReadCalls = MAX_GOALS;
        long evidenceJudgeCalls = deepReadEvidenceStrategy.batchesParentsByGoal()
                ? MAX_GOALS : MAX_EVIDENCE_JUDGE_CALLS;
        long generativeCalls = 1L + parentDeepReadCalls + MAX_EVIDENCE_JUDGE_CALLS
                + parentDeepReadCalls + MAX_FINAL_ANSWER_CALLS;
        long batchedGenerativeCalls = 1L + batchedDeepReadCalls + evidenceJudgeCalls
                + batchedDeepReadCalls + MAX_FINAL_ANSWER_CALLS;
        return switch (dimension) {
            case REQUEST_ANALYSIS_CALL -> 1;
            case PRIMARY_DEEP_READ_CALL -> deepReadEvidenceStrategy.batchesParentsByGoal()
                    ? batchedDeepReadCalls : deepReadEvidenceStrategy.readsParentsIndividually()
                    ? parentDeepReadCalls : MAX_PRIMARY_DEEP_READ_CALLS;
            case REPAIR_DEEP_READ_CALL -> deepReadEvidenceStrategy.batchesParentsByGoal()
                    ? batchedDeepReadCalls : deepReadEvidenceStrategy.readsParentsIndividually()
                    ? parentDeepReadCalls : expandedEvidenceProfile()
                    ? MAX_EXPANDED_REPAIR_DEEP_READ_CALLS : MAX_REPAIR_DEEP_READ_CALLS;
            case EVIDENCE_JUDGE_CALL -> evidenceJudgeCalls;
            case FINAL_ANSWER_CALL -> MAX_FINAL_ANSWER_CALLS;
            case GENERATIVE_LLM_LOGICAL_CALL -> deepReadEvidenceStrategy.batchesParentsByGoal()
                    ? batchedGenerativeCalls : deepReadEvidenceStrategy.readsParentsIndividually()
                    ? generativeCalls : expandedEvidenceProfile()
                    ? MAX_EXPANDED_GENERATIVE_LLM_LOGICAL_CALLS : MAX_GENERATIVE_LLM_LOGICAL_CALLS;
            case GENERATIVE_LLM_PHYSICAL_ATTEMPT -> deepReadEvidenceStrategy.batchesParentsByGoal()
                    ? batchedGenerativeCalls * 2 : deepReadEvidenceStrategy.readsParentsIndividually()
                    ? generativeCalls * 2 : expandedEvidenceProfile()
                    ? MAX_EXPANDED_GENERATIVE_LLM_PHYSICAL_ATTEMPTS : MAX_GENERATIVE_LLM_PHYSICAL_ATTEMPTS;
            case GENERATIVE_LLM_INPUT_TOKEN -> tokens.runInput();
            case GENERATIVE_LLM_OUTPUT_TOKEN -> tokens.runOutput();
            case PHYSICAL_SEARCH -> MAX_PHYSICAL_SEARCHES;
            case SEMANTIC_EMBEDDING_OPERATION -> MAX_SEMANTIC_EMBEDDINGS;
            case RERANK_CALL -> MAX_RERANK_CALLS;
            case PARENT_READ -> (long) retrieval.parentLimitPerGoalPhase() * MAX_RERANK_CALLS;
            case CANDIDATE_SPAN_OFFERED -> (long) retrieval.candidateSpanLimit() * MAX_RERANK_CALLS;
            case ACCEPTED_EVIDENCE -> acceptedEvidenceLimit();
            case FINAL_REFERENCE -> deepReadEvidenceStrategy.batchesParentsByGoal()
                    ? finalAnswerReferenceLimit()
                    : expandedProfile ? 12 : MAX_FINAL_REFERENCES;
            case SERIAL_SEMANTIC_STAGE -> MAX_SERIAL_SEMANTIC_STAGES;
        };
    }

    public record Concurrency(int searches, int reranks, int deepReads) {
        public Concurrency {
            DeepValidation.positiveAtMost(searches, 6, "search concurrency");
            DeepValidation.positiveAtMost(reranks, 3, "rerank concurrency");
            DeepValidation.positiveAtMost(deepReads, 3, "deep read concurrency");
        }

        public static Concurrency defaults() {
            return new Concurrency(6, 3, 3);
        }
    }

    public record Retrieval(
            int keywordTopK,
            int semanticTopK,
            int rrfCandidateLimit,
            int rerankInputLimit,
            int rerankOutputLimit,
            int parentLimitPerGoalPhase,
            int candidateSpanLimit,
            int candidateSpanTokens,
            boolean expandedProfile
    ) {
        public Retrieval(
                int keywordTopK,
                int semanticTopK,
                int rrfCandidateLimit,
                int rerankInputLimit,
                int rerankOutputLimit,
                int parentLimitPerGoalPhase,
                int candidateSpanLimit,
                int candidateSpanTokens
        ) {
            this(keywordTopK, semanticTopK, rrfCandidateLimit, rerankInputLimit, rerankOutputLimit,
                    parentLimitPerGoalPhase, candidateSpanLimit, candidateSpanTokens, false);
        }

        public Retrieval {
            int topKMaximum = expandedProfile ? 60 : 30;
            int rrfMaximum = expandedProfile ? 80 : 40;
            int rerankInputMaximum = expandedProfile ? 80 : 40;
            int rerankOutputMaximum = expandedProfile ? 14 : 8;
            // The expanded profile keeps late but still relevant rerank candidates
            // available for parent expansion and Deep Read.
            int parentMaximum = expandedProfile ? 14 : 4;
            int spanMaximum = expandedProfile ? 16 : 8;
            int spanTokenMaximum = expandedProfile ? 800 : 600;
            DeepValidation.positiveAtMost(keywordTopK, topKMaximum, "keyword topK");
            DeepValidation.positiveAtMost(semanticTopK, topKMaximum, "semantic topK");
            DeepValidation.positiveAtMost(rrfCandidateLimit, rrfMaximum, "RRF candidate limit");
            DeepValidation.positiveAtMost(rerankInputLimit, rerankInputMaximum, "rerank input limit");
            DeepValidation.positiveAtMost(rerankOutputLimit, rerankOutputMaximum, "rerank output limit");
            DeepValidation.positiveAtMost(parentLimitPerGoalPhase, parentMaximum, "parent limit");
            DeepValidation.positiveAtMost(candidateSpanLimit, spanMaximum, "candidate span limit");
            DeepValidation.positiveAtMost(candidateSpanTokens, spanTokenMaximum, "candidate span tokens");
            if (rerankInputLimit > rrfCandidateLimit) {
                throw new IllegalArgumentException("rerank input limit cannot exceed RRF candidate limit");
            }
            if (rerankOutputLimit > rerankInputLimit) {
                throw new IllegalArgumentException("rerank output limit cannot exceed rerank input limit");
            }
        }

        public static Retrieval defaults() {
            return new Retrieval(30, 30, 40, 40, 8, 4, 8, 600, false);
        }

    }

    public record Tokens(
            int conversationInput,
            int requestAnalysisInput,
            int deepReadInput,
            int judgeInput,
            int finalAnswerInput,
            int requestAnalysisOutput,
            int deepReadOutput,
            int judgeOutput,
            int finalAnswerOutput,
            int runInput,
            int runOutput,
            boolean expandedProfile
    ) {
        public Tokens(
                int conversationInput,
                int requestAnalysisInput,
                int deepReadInput,
                int judgeInput,
                int finalAnswerInput,
                int requestAnalysisOutput,
                int deepReadOutput,
                int judgeOutput,
                int finalAnswerOutput,
                int runInput,
                int runOutput
        ) {
            this(conversationInput, requestAnalysisInput, deepReadInput, judgeInput, finalAnswerInput,
                    requestAnalysisOutput, deepReadOutput, judgeOutput, finalAnswerOutput, runInput, runOutput,
                    false);
        }

        public Tokens {
            int requestMaximum = expandedProfile ? 5_000 : 4_000;
            int deepMaximum = expandedProfile ? 20_000 : 6_000;
            int judgeMaximum = expandedProfile ? 10_000 : 8_000;
            int finalMaximum = expandedProfile ? 20_000 : 8_000;
            int analysisOutputMaximum = expandedProfile ? 2_200 : 1_800;
            int deepOutputMaximum = expandedProfile ? 1_800 : 500;
            int judgeOutputMaximum = expandedProfile ? 2_200 : 1_800;
            int finalOutputMaximum = expandedProfile ? 4_000 : 2_000;
            int runInputMaximum = expandedProfile ? 150_000 : 60_000;
            int runOutputMaximum = expandedProfile ? 20_000 : 10_000;
            DeepValidation.positiveAtMost(conversationInput, 2_000, "conversation input tokens");
            DeepValidation.positiveAtMost(requestAnalysisInput, requestMaximum, "request analysis input tokens");
            DeepValidation.positiveAtMost(deepReadInput, deepMaximum, "deep read input tokens");
            DeepValidation.positiveAtMost(judgeInput, judgeMaximum, "judge input tokens");
            DeepValidation.positiveAtMost(finalAnswerInput, finalMaximum, "final answer input tokens");
            DeepValidation.positiveAtMost(requestAnalysisOutput, analysisOutputMaximum, "request analysis output tokens");
            DeepValidation.positiveAtMost(deepReadOutput, deepOutputMaximum, "deep read output tokens");
            DeepValidation.positiveAtMost(judgeOutput, judgeOutputMaximum, "judge output tokens");
            DeepValidation.positiveAtMost(finalAnswerOutput, finalOutputMaximum, "final answer output tokens");
            DeepValidation.positiveAtMost(runInput, runInputMaximum, "run input tokens");
            DeepValidation.positiveAtMost(runOutput, runOutputMaximum, "run output tokens");
        }

        public static Tokens defaults() {
            return new Tokens(2_000, 4_000, 6_000, 8_000, 8_000,
                    1_800, 500, 1_800, 2_000, 60_000, 10_000, false);
        }

    }
}
