package com.yanyue.rag.api.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.chat.RunTraceDetailView;
import com.yanyue.rag.contract.chat.RunTraceGoalView;
import com.yanyue.rag.contract.chat.RunTraceNodeView;
import com.yanyue.rag.contract.chat.RunTraceView;
import com.yanyue.rag.contract.chat.StreamEventType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;

@Service
public class RunTraceService {
    private static final Set<StreamEventType> TRACE_EVENTS = Set.of(
            StreamEventType.QUERY_REWRITE_STARTED,
            StreamEventType.QUERY_REWRITTEN,
            StreamEventType.RETRIEVAL_STARTED,
            StreamEventType.RETRIEVAL_RESULT,
            StreamEventType.RERANK_COMPLETED,
            StreamEventType.RERANK_SKIPPED,
            StreamEventType.PLAN_CREATED,
            StreamEventType.GOAL_RESEARCH_STARTED,
            StreamEventType.GOAL_RESEARCH_COMPLETED,
            StreamEventType.GOAL_RESEARCH_FAILED,
            StreamEventType.EVIDENCE_JUDGE_STARTED,
            StreamEventType.EVIDENCE_JUDGE_COMPLETED,
            StreamEventType.EVIDENCE_JUDGE_FAILED,
            StreamEventType.GAP_IDENTIFIED,
            StreamEventType.GAP_QUERY_CREATED,
            StreamEventType.ANSWER_GENERATION_STARTED,
            StreamEventType.ANSWER_MODE_SELECTED
    );

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public RunTraceService(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public RunTraceView trace(UUID runId) {
        var run = dsl.fetchOptional("""
                SELECT id, organization_id, requested_mode, selected_mode, status, scope, filters,
                       answer_mode, retrieval_health, evidence_count, started_at, completed_at
                FROM rag_run WHERE id = ?
                """, runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
        var events = loadEvents(runId);
        var requestedMode = RunMode.valueOf(run.get("requested_mode", String.class));
        var selectedValue = run.get("selected_mode", String.class);
        var selectedMode = selectedValue == null ? null : RunMode.valueOf(selectedValue);
        var answerModeEvent = last(events, StreamEventType.ANSWER_MODE_SELECTED);
        var answerMode = value(run, "answer_mode");
        if (answerMode == null) answerMode = text(answerModeEvent, "mode", 48);
        var retrievalHealth = value(run, "retrieval_health");
        if (retrievalHealth == null) retrievalHealth = text(answerModeEvent, "retrievalHealth", 48);
        var startedAt = instant(run, "started_at", first(events, StreamEventType.RUN_ACCEPTED));
        var completedAt = instant(run, "completed_at", null);
        var firstAnswerAt = first(events, StreamEventType.ANSWER_DELTA);
        var status = value(run, "status");
        var path = "CONVERSATIONAL".equals(answerMode) ? "CONVERSATIONAL"
                : selectedMode == RunMode.FAST || selectedMode == null && requestedMode == RunMode.FAST
                ? "FAST" : "DEEP";
        var state = switch (status == null ? "RUNNING" : status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> firstAnswerAt == null ? "PROCESSING" : "GENERATING";
        };
        var meaningfulTrace = events.stream().anyMatch(event -> TRACE_EVENTS.contains(event.type()));
        var traceAvailable = meaningfulTrace || !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
        var nodes = switch (path) {
            case "CONVERSATIONAL" -> conversationalNodes(events, status, startedAt, completedAt);
            case "FAST" -> fastNodes(run, events, status, startedAt, completedAt, answerMode, retrievalHealth);
            default -> deepNodes(runId, run, events, status, startedAt, completedAt, answerMode, retrievalHealth);
        };
        return new RunTraceView(
                runId,
                requestedMode,
                selectedMode,
                path,
                state,
                startedAt,
                firstAnswerAt,
                completedAt,
                duration(startedAt, completedAt),
                traceAvailable,
                answerMode,
                retrievalHealth,
                run.get("evidence_count", Integer.class) == null
                        ? integer(answerModeEvent, "evidenceCount", 0)
                        : run.get("evidence_count", Integer.class),
                traceAvailable ? nodes : List.of()
        );
    }

    public boolean shouldRefresh(StreamEventType type) {
        return TRACE_EVENTS.contains(type)
                || type == StreamEventType.RUN_ACCEPTED
                || type == StreamEventType.RUN_RECOVERED
                || type == StreamEventType.ROUTE_SELECTED;
    }

    private List<RunTraceNodeView> conversationalNodes(
            List<Event> events,
            String runStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        var generationStart = first(events, StreamEventType.ANSWER_GENERATION_STARTED,
                StreamEventType.ANSWER_MODE_SELECTED, StreamEventType.ANSWER_DELTA);
        return List.of(
                node("understand", "理解问题", stageStatus(startedAt, generationStart, runStatus, false),
                        generationStart == null ? "正在理解你的问题" : "已识别为普通对话",
                        startedAt, generationStart, List.of(), List.of()),
                node("generate", "生成回答",
                        stageStatus(generationStart, terminalEnd(runStatus, completedAt), runStatus, false),
                        answerSummary(runStatus, "CONVERSATIONAL", null), generationStart,
                        terminalEnd(runStatus, completedAt), List.of(), List.of())
        );
    }

    private List<RunTraceNodeView> fastNodes(
            Record run,
            List<Event> events,
            String runStatus,
            Instant startedAt,
            Instant completedAt,
            String answerMode,
            String retrievalHealth
    ) {
        var result = new ArrayList<RunTraceNodeView>();
        var rewriteEvent = last(events, StreamEventType.QUERY_REWRITTEN);
        var rewriteStart = first(events, StreamEventType.QUERY_REWRITE_STARTED);
        if (rewriteStart == null) rewriteStart = first(events, StreamEventType.ROUTE_SELECTED);
        if (rewriteStart == null) rewriteStart = startedAt;
        var retrievalStart = first(events, StreamEventType.RETRIEVAL_STARTED);
        var rewriteEnd = rewriteEvent == null ? retrievalStart : rewriteEvent.timestamp();
        var rewriteDegraded = bool(rewriteEvent, "fallback");
        var rewriteSummary = rewriteEvent == null
                ? retrievalStart == null ? "正在判断是否需要改写" : "无需改写"
                : rewriteDegraded ? "改写不可用，已使用原问题"
                : same(rewriteEvent, "original", "rewritten") ? "无需改写" : "已生成独立检索问题";
        var rewriteDetails = new ArrayList<RunTraceDetailView>();
        if (rewriteEvent != null) add(rewriteDetails, "检索问题", text(rewriteEvent, "rewritten", 320));
        result.add(node("rewrite", "问题改写",
                stageStatus(rewriteStart, rewriteEnd, runStatus, rewriteDegraded), rewriteSummary,
                rewriteStart, rewriteEnd, rewriteDetails, List.of()));

        var retrievalResult = last(events, StreamEventType.RETRIEVAL_RESULT);
        var retrievalEnd = retrievalResult == null ? null : retrievalResult.timestamp();
        var retrievalDetails = scopeDetails(run);
        add(retrievalDetails, "关键词命中", number(retrievalResult, "keywordCount"));
        add(retrievalDetails, "语义命中", number(retrievalResult, "semanticCount"));
        add(retrievalDetails, "合并候选", number(retrievalResult, "candidateCount"));
        var retrievalSummary = retrievalResult == null
                ? "正在并行检索关键词与语义结果"
                : "%s 个合并候选".formatted(number(retrievalResult, "candidateCount", 0));
        result.add(node("retrieve", "混合检索",
                stageStatus(retrievalStart, retrievalEnd, runStatus, false), retrievalSummary,
                retrievalStart, retrievalEnd, retrievalDetails, List.of()));

        var rerank = last(events, StreamEventType.RERANK_COMPLETED, StreamEventType.RERANK_SKIPPED);
        var rerankEnd = rerank == null ? null : rerank.timestamp();
        var rerankDegraded = rerank != null && rerank.type() == StreamEventType.RERANK_SKIPPED;
        var rerankDetails = new ArrayList<RunTraceDetailView>();
        add(rerankDetails, "输入候选", number(retrievalResult, "candidateCount"));
        add(rerankDetails, "排序后保留", number(rerank, "candidateCount"));
        if (rerankDegraded) add(rerankDetails, "降级策略", "使用混合检索顺序");
        var rerankSummary = rerank == null ? "等待候选结果"
                : rerankDegraded ? "排序服务不可用，已使用混合检索顺序"
                : "%s 个候选完成相关性排序".formatted(number(rerank, "candidateCount", 0));
        result.add(node("rerank", "相关性排序",
                stageStatus(retrievalEnd, rerankEnd, runStatus, rerankDegraded), rerankSummary,
                retrievalEnd, rerankEnd, rerankDetails, List.of()));

        var generationStart = first(events, StreamEventType.ANSWER_GENERATION_STARTED,
                StreamEventType.ANSWER_MODE_SELECTED, StreamEventType.ANSWER_DELTA);
        result.add(node("generate", "生成回答",
                stageStatus(generationStart, terminalEnd(runStatus, completedAt), runStatus, false),
                answerSummary(runStatus, answerMode, retrievalHealth), generationStart,
                terminalEnd(runStatus, completedAt), answerDetails(answerMode, retrievalHealth), List.of()));
        return List.copyOf(result);
    }

    private List<RunTraceNodeView> deepNodes(
            UUID runId,
            Record run,
            List<Event> events,
            String runStatus,
            Instant startedAt,
            Instant completedAt,
            String answerMode,
            String retrievalHealth
    ) {
        var result = new ArrayList<RunTraceNodeView>();
        var plan = last(events, StreamEventType.PLAN_CREATED);
        var planStart = first(events, StreamEventType.ROUTE_SELECTED);
        if (planStart == null) planStart = startedAt;
        var planEnd = plan == null ? null : plan.timestamp();
        var planGoals = planGoals(plan);
        var planDetails = new ArrayList<RunTraceDetailView>();
        add(planDetails, "独立问题", text(plan, "standaloneObjective", 360));
        add(planDetails, "研究目标", planGoals.isEmpty() ? null : planGoals.size() + " 个");
        result.add(node("plan", "制定计划", stageStatus(planStart, planEnd, runStatus, false),
                plan == null ? "正在拆解研究目标" : "%d 个研究目标".formatted(planGoals.size()),
                planStart, planEnd, planDetails, List.of()));

        var primaryStarts = matching(events, StreamEventType.GOAL_RESEARCH_STARTED, "phase", "PRIMARY");
        var primaryEnds = matching(events,
                Set.of(StreamEventType.GOAL_RESEARCH_COMPLETED, StreamEventType.GOAL_RESEARCH_FAILED),
                "phase", "PRIMARY");
        var parallelStart = firstTimestamp(primaryStarts);
        var judgeStart = first(events, StreamEventType.EVIDENCE_JUDGE_STARTED);
        var parallelEnd = judgeStart == null && primaryStarts.size() == primaryEnds.size() && !primaryEnds.isEmpty()
                ? lastTimestamp(primaryEnds) : judgeStart;
        var goalViews = goalViews(planGoals, primaryStarts, primaryEnds);
        var failedGoals = (int) primaryEnds.stream()
                .filter(event -> event.type() == StreamEventType.GOAL_RESEARCH_FAILED).count();
        var completedGoals = primaryEnds.size();
        var queryCount = primaryStarts.stream().mapToInt(event -> integer(event, "queryCount", 0)).sum();
        var parallelDetails = scopeDetails(run);
        add(parallelDetails, "执行查询", queryCount == 0 ? null : queryCount + " 条");
        var parallelSummary = primaryStarts.isEmpty() ? "等待研究计划"
                : "%d/%d 个目标完成".formatted(completedGoals, primaryStarts.size());
        result.add(node("parallel-retrieval", "并行检索",
                stageStatus(parallelStart, parallelEnd, runStatus, failedGoals > 0), parallelSummary,
                parallelStart, parallelEnd, parallelDetails, goalViews));

        var evidenceCount = primaryEnds.stream().mapToInt(event -> integer(event, "acceptedEvidenceCount", 0)).sum();
        var collectStart = firstTimestamp(primaryEnds);
        var collectEnd = judgeStart;
        var documentCount = dsl.fetchValue("""
                SELECT count(DISTINCT document_version_id) FROM evidence_item
                WHERE run_id = ?
                """, runId, Integer.class);
        var collectDetails = new ArrayList<RunTraceDetailView>();
        add(collectDetails, "有效证据", evidenceCount + " 条");
        add(collectDetails, "涉及文档", documentCount == null ? null : documentCount + " 篇");
        add(collectDetails, "覆盖目标", completedGoals + "/" + Math.max(primaryStarts.size(), planGoals.size()));
        result.add(node("collect", "收集证据", stageStatus(collectStart, collectEnd, runStatus, failedGoals > 0),
                collectStart == null ? "等待检索结果" : "%d 条有效证据".formatted(evidenceCount),
                collectStart, collectEnd, collectDetails, List.of()));

        var judge = last(events, StreamEventType.EVIDENCE_JUDGE_COMPLETED, StreamEventType.EVIDENCE_JUDGE_FAILED);
        var judgeEnd = judge == null ? null : judge.timestamp();
        var judgeFailed = judge != null && judge.type() == StreamEventType.EVIDENCE_JUDGE_FAILED;
        var judgeDegraded = judgeFailed || bool(judge, "degraded");
        var gaps = matching(events, StreamEventType.GAP_IDENTIFIED);
        var judgeDetails = new ArrayList<RunTraceDetailView>();
        add(judgeDetails, "证据结论", verdict(judge, gaps));
        add(judgeDetails, "证据缺口", gaps.isEmpty() ? "无" : gaps.size() + " 个");
        var judgeSummary = judge == null ? "等待证据汇合"
                : judgeFailed ? "证据判断降级"
                : gaps.isEmpty() ? "证据充分" : "%d 个证据缺口".formatted(gaps.size());
        result.add(node("verify", "验证证据", stageStatus(judgeStart, judgeEnd, runStatus, judgeDegraded),
                judgeSummary, judgeStart, judgeEnd, judgeDetails, List.of()));

        var repairStarts = matching(events, StreamEventType.GOAL_RESEARCH_STARTED, "phase", "REPAIR");
        var repairEnds = matching(events,
                Set.of(StreamEventType.GOAL_RESEARCH_COMPLETED, StreamEventType.GOAL_RESEARCH_FAILED),
                "phase", "REPAIR");
        if (!gaps.isEmpty() || !repairStarts.isEmpty()) {
            var repairStart = gaps.isEmpty() ? firstTimestamp(repairStarts) : firstTimestamp(gaps);
            var generationStart = first(events, StreamEventType.ANSWER_GENERATION_STARTED,
                    StreamEventType.ANSWER_MODE_SELECTED, StreamEventType.ANSWER_DELTA);
            var repairEnd = !repairEnds.isEmpty() && repairEnds.size() >= repairStarts.size()
                    ? lastTimestamp(repairEnds) : generationStart;
            var repairEvidence = repairEnds.stream()
                    .mapToInt(event -> integer(event, "acceptedEvidenceCount", 0)).sum();
            var repairFailed = repairEnds.stream()
                    .anyMatch(event -> event.type() == StreamEventType.GOAL_RESEARCH_FAILED);
            var repairDetails = new ArrayList<RunTraceDetailView>();
            add(repairDetails, "补充目标", Math.max(gaps.size(), repairStarts.size()) + " 个");
            add(repairDetails, "追加查询", repairStarts.stream()
                    .mapToInt(event -> integer(event, "queryCount", 0)).sum() + " 条");
            add(repairDetails, "新增证据", repairEvidence + " 条");
            result.add(node("repair", "补充检索",
                    stageStatus(repairStart, repairEnd, runStatus, repairFailed),
                    repairEnd == null ? "正在补充证据缺口" : "%d 条新增证据".formatted(repairEvidence),
                    repairStart, repairEnd, repairDetails, List.of()));
        }

        var generationStart = first(events, StreamEventType.ANSWER_GENERATION_STARTED,
                StreamEventType.ANSWER_MODE_SELECTED, StreamEventType.ANSWER_DELTA);
        result.add(node("generate", "生成回答",
                stageStatus(generationStart, terminalEnd(runStatus, completedAt), runStatus, false),
                answerSummary(runStatus, answerMode, retrievalHealth), generationStart,
                terminalEnd(runStatus, completedAt), answerDetails(answerMode, retrievalHealth), List.of()));
        return List.copyOf(result);
    }

    private List<Event> loadEvents(UUID runId) {
        return dsl.fetch("""
                SELECT event_type, payload::text AS payload, created_at
                FROM rag_run_event WHERE run_id = ? ORDER BY sequence
                """, runId).map(record -> new Event(
                StreamEventType.valueOf(record.get("event_type", String.class)),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                payload(record.get("payload", String.class))));
    }

    private Map<String, Object> payload(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private List<PlanGoal> planGoals(Event plan) {
        if (plan == null || !(plan.payload().get("goals") instanceof List<?> values)) return List.of();
        var result = new ArrayList<PlanGoal>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> goal)) continue;
            var id = string(goal.get("id"));
            var question = string(goal.get("question"));
            if (id != null) result.add(new PlanGoal(id, compact(question, 120)));
        }
        return List.copyOf(result);
    }

    private List<RunTraceGoalView> goalViews(
            List<PlanGoal> planGoals,
            List<Event> starts,
            List<Event> ends
    ) {
        var order = new LinkedHashMap<String, String>();
        planGoals.forEach(goal -> order.put(goal.id(), goal.question()));
        starts.forEach(event -> order.putIfAbsent(text(event, "goalId", 80), null));
        var result = new ArrayList<RunTraceGoalView>();
        int index = 0;
        for (var entry : order.entrySet()) {
            index++;
            var end = ends.stream().filter(event -> entry.getKey().equals(text(event, "goalId", 80)))
                    .reduce((left, right) -> right).orElse(null);
            var started = starts.stream().anyMatch(event -> entry.getKey().equals(text(event, "goalId", 80)));
            var status = end == null ? started ? "RUNNING" : "WAITING"
                    : end.type() == StreamEventType.GOAL_RESEARCH_FAILED ? "DEGRADED" : "COMPLETED";
            var evidence = integer(end, "acceptedEvidenceCount", 0);
            var summary = end == null ? started ? "检索中" : "等待中"
                    : evidence == 0 ? "未形成有效证据" : evidence + " 条有效证据";
            result.add(new RunTraceGoalView(index,
                    entry.getValue() == null || entry.getValue().isBlank() ? "研究目标 " + index : entry.getValue(),
                    status, summary));
        }
        return List.copyOf(result);
    }

    private List<RunTraceDetailView> scopeDetails(Record run) {
        var result = new ArrayList<RunTraceDetailView>();
        var scope = jsonMap(run.get("scope", JSONB.class));
        var knowledgeBaseIds = list(scope.get("knowledgeBaseIds"));
        var documentIds = list(scope.get("documentIds"));
        var scopeLabel = knowledgeBaseIds.isEmpty() && documentIds.isEmpty() ? "全部可访问知识"
                : !knowledgeBaseIds.isEmpty() ? knowledgeBaseIds.size() + " 个知识库"
                : documentIds.size() + " 篇指定文档";
        add(result, "知识范围", scopeLabel);
        var filters = jsonList(run.get("filters", JSONB.class));
        add(result, "Metadata 条件", filters.isEmpty() ? "无" : filterSummary(filters));
        return result;
    }

    private Map<String, Object> jsonMap(JSONB value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> jsonList(JSONB value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String filterSummary(List<Map<String, Object>> filters) {
        return filters.stream().limit(3).map(filter -> {
            var field = string(filter.get("field"));
            var operator = switch (String.valueOf(filter.get("operator"))) {
                case "EQ" -> "等于";
                case "NE" -> "不等于";
                case "GT" -> "晚于/大于";
                case "GTE" -> "不早于/不小于";
                case "LT" -> "早于/小于";
                case "LTE" -> "不晚于/不大于";
                case "IN" -> "任一为";
                case "CONTAINS" -> "包含";
                default -> "匹配";
            };
            return (field == null ? "字段" : field) + " " + operator + " " + compact(string(filter.get("value")), 80);
        }).collect(java.util.stream.Collectors.joining("；"))
                + (filters.size() > 3 ? "；另有 " + (filters.size() - 3) + " 项" : "");
    }

    private List<RunTraceDetailView> answerDetails(String answerMode, String retrievalHealth) {
        var details = new ArrayList<RunTraceDetailView>();
        add(details, "资料使用", switch (answerMode == null ? "" : answerMode) {
            case "GROUNDED", "ANSWER_WITH_EVIDENCE" -> "内部资料支撑";
            case "PARTIAL_GROUNDED" -> "部分内部资料支撑";
            case "CONVERSATIONAL" -> "普通对话，无需检索";
            default -> "未引用内部资料";
        });
        if ("DEGRADED".equals(retrievalHealth)) add(details, "链路状态", "部分检索能力已降级");
        return details;
    }

    private String answerSummary(String runStatus, String answerMode, String retrievalHealth) {
        if ("FAILED".equals(runStatus)) return "未能完成最终回答";
        if ("CANCELLED".equals(runStatus)) return "已停止生成";
        if (!"COMPLETED".equals(runStatus)) return "正在组织回答";
        if ("DEGRADED".equals(retrievalHealth)) return "已在降级状态下完成回答";
        return switch (answerMode == null ? "" : answerMode) {
            case "GROUNDED", "ANSWER_WITH_EVIDENCE" -> "已结合内部证据完成回答";
            case "PARTIAL_GROUNDED" -> "已结合部分内部证据完成回答";
            case "CONVERSATIONAL" -> "已完成回答";
            default -> "已使用通用知识完成回答";
        };
    }

    private String verdict(Event judge, List<Event> gaps) {
        if (judge == null) return null;
        if (judge.type() == StreamEventType.EVIDENCE_JUDGE_FAILED || bool(judge, "degraded")) return "降级判断";
        return gaps.isEmpty() ? "充分" : "部分充分";
    }

    private RunTraceNodeView node(
            String key,
            String label,
            String status,
            String summary,
            Instant startedAt,
            Instant completedAt,
            List<RunTraceDetailView> details,
            List<RunTraceGoalView> goals
    ) {
        return new RunTraceNodeView(key, label, status, summary, startedAt, completedAt,
                duration(startedAt, completedAt), details, goals);
    }

    private String stageStatus(Instant start, Instant end, String runStatus, boolean degraded) {
        if (start == null) return "WAITING";
        if (end != null) return degraded ? "DEGRADED" : "COMPLETED";
        if ("FAILED".equals(runStatus)) return "FAILED";
        if ("CANCELLED".equals(runStatus)) return "CANCELLED";
        return "RUNNING";
    }

    private Instant terminalEnd(String status, Instant completedAt) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status) ? completedAt : null;
    }

    private Long duration(Instant start, Instant end) {
        if (start == null || end == null) return null;
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private Instant instant(Record record, String field, Instant fallback) {
        var value = record.get(field, OffsetDateTime.class);
        return value == null ? fallback : value.toInstant();
    }

    private String value(Record record, String field) {
        return record.get(field, String.class);
    }

    private Instant first(List<Event> events, StreamEventType... types) {
        var accepted = Set.copyOf(Arrays.asList(types));
        return events.stream().filter(event -> accepted.contains(event.type()))
                .map(Event::timestamp).findFirst().orElse(null);
    }

    private Event last(List<Event> events, StreamEventType... types) {
        var accepted = Set.copyOf(Arrays.asList(types));
        Event found = null;
        for (var event : events) if (accepted.contains(event.type())) found = event;
        return found;
    }

    private List<Event> matching(List<Event> events, StreamEventType type) {
        return events.stream().filter(event -> event.type() == type).toList();
    }

    private List<Event> matching(List<Event> events, StreamEventType type, String key, String expected) {
        return matching(events, Set.of(type), key, expected);
    }

    private List<Event> matching(
            List<Event> events,
            Set<StreamEventType> types,
            String key,
            String expected
    ) {
        return events.stream().filter(event -> types.contains(event.type()))
                .filter(event -> expected.equalsIgnoreCase(text(event, key, 80))).toList();
    }

    private Instant firstTimestamp(List<Event> events) {
        return events.isEmpty() ? null : events.getFirst().timestamp();
    }

    private Instant lastTimestamp(List<Event> events) {
        return events.isEmpty() ? null : events.getLast().timestamp();
    }

    private boolean same(Event event, String left, String right) {
        if (event == null) return false;
        return String.valueOf(event.payload().get(left)).equals(String.valueOf(event.payload().get(right)));
    }

    private boolean bool(Event event, String key) {
        return event != null && Boolean.TRUE.equals(event.payload().get(key));
    }

    private int integer(Event event, String key, int fallback) {
        if (event == null) return fallback;
        var value = event.payload().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private String number(Event event, String key) {
        if (event == null || !(event.payload().get(key) instanceof Number number)) return null;
        return Integer.toString(number.intValue());
    }

    private int number(Event event, String key, int fallback) {
        return integer(event, key, fallback);
    }

    private String text(Event event, String key, int maximum) {
        return event == null ? null : compact(string(event.payload().get(key)), maximum);
    }

    private void add(List<RunTraceDetailView> details, String label, Object value) {
        if (value == null || String.valueOf(value).isBlank()) return;
        details.add(new RunTraceDetailView(label, String.valueOf(value)));
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String string(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String compact(String value, int maximum) {
        if (value == null) return null;
        var normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum - 1) + "…";
    }

    private record Event(StreamEventType type, Instant timestamp, Map<String, Object> payload) {
    }

    private record PlanGoal(String id, String question) {
    }
}
