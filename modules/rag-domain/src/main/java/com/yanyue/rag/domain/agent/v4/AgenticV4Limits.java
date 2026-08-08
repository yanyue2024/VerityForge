package com.yanyue.rag.domain.agent.v4;

import java.time.Duration;

public record AgenticV4Limits(
        Concurrency concurrency,
        Retrieval retrieval,
        Tokens tokens,
        Duration runDeadline
) implements com.yanyue.rag.domain.agent.AgentBudgetLimits {
    public static final int MAX_GOALS = 3;
    public static final int MAX_OBJECTIVE_REQUIREMENTS = 3;
    public static final int MAX_REQUIREMENTS_PER_GOAL = 3;
    public static final int MAX_PRIMARY_QUERIES = 3;
    public static final int MAX_REPAIR_QUERIES_PER_GOAL = 2;
    public static final int MAX_REPAIR_QUERIES = 6;
    public static final int MAX_PHYSICAL_SEARCHES = 9;
    public static final int MAX_SEMANTIC_EMBEDDINGS = 6;
    public static final int MAX_RERANK_CALLS = 6;
    public static final int MAX_PRIMARY_DEEP_READ_CALLS = 3;
    public static final int MAX_REPAIR_DEEP_READ_CALLS = 3;
    public static final int MAX_DEEP_READ_CALLS = 6;
    public static final int MAX_EVIDENCE_JUDGE_CALLS = 1;
    public static final int MAX_FINAL_ANSWER_CALLS = 1;
    public static final int MAX_GENERATIVE_LLM_LOGICAL_CALLS = 9;
    public static final int MAX_SERIAL_SEMANTIC_STAGES = 5;
    public static final int MAX_GENERATIVE_LLM_PHYSICAL_ATTEMPTS = 12;
    public static final int MAX_PARENT_READS = 24;
    public static final int MAX_CANDIDATE_SPANS_OFFERED = 48;
    public static final int MAX_ACCEPTED_EVIDENCE = 18;
    public static final int MAX_FINAL_REFERENCES = 8;
    public static final int MAX_EVIDENCE_PER_GOAL = 6;
    public static final int MAX_EVIDENCE_PER_REQUIREMENT = 2;
    public static final int MAX_EVIDENCE_PER_PARENT_AND_PHASE = 2;

    public AgenticV4Limits {
        V4Validation.required(concurrency, "concurrency");
        V4Validation.required(retrieval, "retrieval");
        V4Validation.required(tokens, "tokens");
        V4Validation.required(runDeadline, "runDeadline");
        if (runDeadline.isZero() || runDeadline.isNegative() || runDeadline.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("runDeadline must be positive and at most 120 seconds");
        }
    }

    public static AgenticV4Limits defaults() {
        return new AgenticV4Limits(Concurrency.defaults(), Retrieval.defaults(), Tokens.defaults(),
                Duration.ofSeconds(120));
    }

    @Override
    public long maximum(BudgetDimension dimension) {
        return switch (dimension) {
            case REQUEST_ANALYSIS_CALL -> 1;
            case PRIMARY_DEEP_READ_CALL -> MAX_PRIMARY_DEEP_READ_CALLS;
            case REPAIR_DEEP_READ_CALL -> MAX_REPAIR_DEEP_READ_CALLS;
            case EVIDENCE_JUDGE_CALL -> MAX_EVIDENCE_JUDGE_CALLS;
            case FINAL_ANSWER_CALL -> MAX_FINAL_ANSWER_CALLS;
            case GENERATIVE_LLM_LOGICAL_CALL -> MAX_GENERATIVE_LLM_LOGICAL_CALLS;
            case GENERATIVE_LLM_PHYSICAL_ATTEMPT -> MAX_GENERATIVE_LLM_PHYSICAL_ATTEMPTS;
            case GENERATIVE_LLM_INPUT_TOKEN -> tokens.runInput();
            case GENERATIVE_LLM_OUTPUT_TOKEN -> tokens.runOutput();
            case PHYSICAL_SEARCH -> MAX_PHYSICAL_SEARCHES;
            case SEMANTIC_EMBEDDING_OPERATION -> MAX_SEMANTIC_EMBEDDINGS;
            case RERANK_CALL -> MAX_RERANK_CALLS;
            case PARENT_READ -> MAX_PARENT_READS;
            case CANDIDATE_SPAN_OFFERED -> MAX_CANDIDATE_SPANS_OFFERED;
            case ACCEPTED_EVIDENCE -> MAX_ACCEPTED_EVIDENCE;
            case FINAL_REFERENCE -> MAX_FINAL_REFERENCES;
            case SERIAL_SEMANTIC_STAGE -> MAX_SERIAL_SEMANTIC_STAGES;
        };
    }

    public record Concurrency(int searches, int reranks, int deepReads) {
        public Concurrency {
            positiveAtMost(searches, 6, "search concurrency");
            positiveAtMost(reranks, 3, "rerank concurrency");
            positiveAtMost(deepReads, 3, "deep read concurrency");
        }

        public static Concurrency defaults() {
            return new Concurrency(6, 3, 3);
        }
    }

    public record Retrieval(int singleRouteTopK, int candidateSpanTokens) {
        public Retrieval {
            positiveAtMost(singleRouteTopK, 12, "single route topK");
            positiveAtMost(candidateSpanTokens, 600, "candidate span tokens");
        }

        public static Retrieval defaults() {
            return new Retrieval(12, 600);
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
            int runOutput
    ) {
        public Tokens {
            positiveAtMost(conversationInput, 2_000, "conversation input tokens");
            positiveAtMost(requestAnalysisInput, 4_000, "request analysis input tokens");
            positiveAtMost(deepReadInput, 6_000, "deep read input tokens");
            positiveAtMost(judgeInput, 8_000, "judge input tokens");
            positiveAtMost(finalAnswerInput, 8_000, "final answer input tokens");
            positiveAtMost(requestAnalysisOutput, 1_800, "request analysis output tokens");
            positiveAtMost(deepReadOutput, 500, "deep read output tokens");
            positiveAtMost(judgeOutput, 1_800, "judge output tokens");
            positiveAtMost(finalAnswerOutput, 2_000, "final answer output tokens");
            positiveAtMost(runInput, 60_000, "run input tokens");
            positiveAtMost(runOutput, 10_000, "run output tokens");
        }

        public static Tokens defaults() {
            return new Tokens(2_000, 4_000, 6_000, 8_000, 8_000,
                    1_800, 500, 1_800, 2_000, 60_000, 10_000);
        }
    }

    private static void positiveAtMost(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
    }
}
