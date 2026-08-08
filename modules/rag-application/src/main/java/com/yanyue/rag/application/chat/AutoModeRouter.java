package com.yanyue.rag.application.chat;

import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Zero-model-cost admission gate for AUTO requests. Deep signals veto every
 * Fast signal; uncertain questions deliberately fail toward Deep.
 */
@Service
public class AutoModeRouter {
    private static final Pattern MULTI_GOAL = Pattern.compile(
            "同时|分别|依次|独立目标|(?:两个|三个|多个)(?:独立)?目标|多意图|各自|逐一|不要.{0,8}合并");
    private static final Pattern STAGED_TASK = Pattern.compile(
            "第一阶段|第二阶段|第三阶段|三阶段|多阶段|分阶段|依次处理");
    private static final Pattern SYNTHESIS = Pattern.compile(
            "综合|跨文档|多份(?:文档|资料)|多个(?:文档|资料)|汇总.{0,8}(?:文档|资料)");
    private static final Pattern COMPARISON = Pattern.compile(
            "比较|对比|差异|异同|权衡|优缺点|优势.{0,6}劣势|区别");
    private static final Pattern CONFLICT = Pattern.compile(
            "冲突|矛盾|相互印证|交叉验证");
    private static final Pattern CAUSE = Pattern.compile("根因|原因|为什么|为何");
    private static final Pattern REMEDIATION = Pattern.compile("修复|解决|方案|措施|处置|恢复|改进");
    private static final Pattern DIRECT_DOCUMENT_LOOKUP = Pattern.compile(
            "根据《|在《|文档.{0,8}(?:说明|给出)|部分.{0,8}(?:关键信息|要求|做法)|"
                    + "(?:关键信息|要求|做法)是什么");
    private static final Pattern SIMPLE_AVAILABILITY_LOOKUP = Pattern.compile(
            "是否.{0,16}(?:给出|包含|规定|说明|提供)|有没有.{0,16}(?:给出|包含|规定|说明|提供)");
    private static final Pattern TECHNICAL_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([A-Za-z][A-Za-z0-9_.-]{2,})(?![A-Za-z0-9_.-])");
    private static final Set<String> GENERIC_ENGLISH = Set.of(
            "what", "how", "why", "which", "when", "who", "the", "and", "for", "from", "into", "with",
            "without", "does", "are", "can", "should");
    private final Profile profile;

    public AutoModeRouter() {
        this(Profile.RETRIEVAL_AWARE_50);
    }

    @Autowired
    public AutoModeRouter(@Value("${rag.routing.profile:RETRIEVAL_AWARE_50}") String profile) {
        this(Profile.parse(profile));
    }

    AutoModeRouter(Profile profile) {
        this.profile = profile == null ? Profile.RETRIEVAL_AWARE_50 : profile;
    }

    public Decision route(String rawQuery) {
        try {
            return decide(normalize(rawQuery));
        } catch (RuntimeException ignored) {
            return decision(RunMode.DEEP, "auto-deep-router-error", List.of("ROUTER_ERROR"), -1);
        }
    }

    public Decision route(String rawQuery, List<RetrievalHit> fusedCandidates) {
        try {
            var query = normalize(rawQuery);
            var initial = decide(query);
            if (initial.mode() == RunMode.FAST || !canUseRetrievalEvidence(initial)) return initial;

            int titleHitCount = titleHitCount(query, fusedCandidates);
            int requiredHits = requiredTitleHits(initial.signals());
            if (titleHitCount >= requiredHits) {
                return decision(RunMode.FAST, profile.fastReason(), initial.signals(), titleHitCount);
            }
            return decision(RunMode.DEEP, "auto-deep-retrieval-confidence", initial.signals(), titleHitCount);
        } catch (RuntimeException ignored) {
            return retrievalFailure();
        }
    }

    public boolean canUseRetrievalEvidence(Decision initial) {
        return initial != null
                && initial.mode() == RunMode.DEEP
                && (initial.signals().contains("MULTI_GOAL") || initial.signals().contains("STAGED_TASK"));
    }

    public Decision retrievalFailure() {
        return decision(RunMode.DEEP, "auto-deep-retrieval-preflight-error",
                List.of("RETRIEVAL_PREFLIGHT_ERROR"), -1);
    }

    public Profile profile() {
        return profile;
    }

    private Decision decide(String query) {
        if (query.isEmpty()) {
            return decision(RunMode.DEEP, "auto-deep-empty", List.of("EMPTY_QUERY"), -1);
        }

        var deepSignals = deepSignals(query);
        if (!deepSignals.isEmpty()) {
            return decision(RunMode.DEEP, deepReason(deepSignals.getFirst()), deepSignals, -1);
        }

        int documentReferences = documentReferenceCount(query);
        if (documentReferences == 1 && DIRECT_DOCUMENT_LOOKUP.matcher(query).find()) {
            return decision(RunMode.FAST, "auto-fast-explicit-document",
                    List.of("SINGLE_DOCUMENT_LOOKUP"), -1);
        }
        if (SIMPLE_AVAILABILITY_LOOKUP.matcher(query).find()) {
            return decision(RunMode.FAST, "auto-fast-simple-lookup",
                    List.of("SIMPLE_AVAILABILITY_LOOKUP"), -1);
        }

        var technicalAnchors = technicalAnchors(query);
        if (!technicalAnchors.isEmpty()) {
            return decision(RunMode.FAST, "auto-fast-technical-anchor",
                    List.of("STABLE_TECHNICAL_ANCHOR"), -1);
        }
        return decision(RunMode.DEEP, "auto-deep-uncertain", List.of("UNCERTAIN"), -1);
    }

    private int requiredTitleHits(List<String> signals) {
        if (signals.contains("STAGED_TASK")) return 2;
        return profile == Profile.RETRIEVAL_AWARE_28 ? 2 : 1;
    }

    private int titleHitCount(String query, List<RetrievalHit> fusedCandidates) {
        if (query.isEmpty() || fusedCandidates == null || fusedCandidates.isEmpty()) return 0;
        var normalizedQuery = normalizeComparable(query);
        var titles = new LinkedHashSet<String>();
        fusedCandidates.stream().limit(5).forEach(hit -> {
            var title = normalizeComparable(hit == null ? null : hit.documentTitle());
            if (title.length() >= 4 && normalizedQuery.contains(title)) titles.add(title);
        });
        return titles.size();
    }

    private String normalizeComparable(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private List<String> deepSignals(String query) {
        var signals = new LinkedHashSet<String>();
        if (documentReferenceCount(query) >= 2) signals.add("MULTI_DOCUMENT");
        if (MULTI_GOAL.matcher(query).find()) signals.add("MULTI_GOAL");
        if (STAGED_TASK.matcher(query).find()) signals.add("STAGED_TASK");
        if (SYNTHESIS.matcher(query).find()) signals.add("SYNTHESIS");
        if (COMPARISON.matcher(query).find()) signals.add("COMPARISON");
        if (CONFLICT.matcher(query).find()) signals.add("CONFLICT");
        if (CAUSE.matcher(query).find() && REMEDIATION.matcher(query).find()) {
            signals.add("DIAGNOSE_AND_REPAIR");
        }
        return List.copyOf(signals);
    }

    private List<String> technicalAnchors(String query) {
        var anchors = new ArrayList<String>();
        var matcher = TECHNICAL_TOKEN.matcher(query);
        while (matcher.find()) {
            var value = matcher.group(1);
            if (!GENERIC_ENGLISH.contains(value.toLowerCase(Locale.ROOT))) anchors.add(value);
        }
        return List.copyOf(anchors);
    }

    private int documentReferenceCount(String query) {
        int count = 0;
        int offset = 0;
        while (offset < query.length()) {
            int open = query.indexOf('《', offset);
            if (open < 0) break;
            int close = query.indexOf('》', open + 1);
            if (close < 0) break;
            count++;
            offset = close + 1;
        }
        return count;
    }

    private String deepReason(String primarySignal) {
        return switch (primarySignal) {
            case "MULTI_DOCUMENT" -> "auto-deep-multi-document";
            case "MULTI_GOAL" -> "auto-deep-multi-goal";
            case "STAGED_TASK" -> "auto-deep-staged-task";
            case "SYNTHESIS" -> "auto-deep-synthesis";
            case "COMPARISON" -> "auto-deep-comparison";
            case "CONFLICT" -> "auto-deep-conflict";
            case "DIAGNOSE_AND_REPAIR" -> "auto-deep-diagnose-repair";
            default -> "auto-deep-uncertain";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private Decision decision(RunMode mode, String reasonCode, List<String> signals, int titleHitCount) {
        return new Decision(mode, reasonCode, signals, profile, titleHitCount);
    }

    public record Decision(
            RunMode mode,
            String reasonCode,
            List<String> signals,
            Profile profile,
            int titleHitCount
    ) {
        public Decision {
            if (mode == null || mode == RunMode.AUTO) throw new IllegalArgumentException("mode must be FAST or DEEP");
            if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode is required");
            signals = signals == null ? List.of() : List.copyOf(signals);
            if (profile == null) throw new IllegalArgumentException("profile is required");
            if (titleHitCount < -1) throw new IllegalArgumentException("titleHitCount must be >= -1");
        }
    }

    public enum Profile {
        RETRIEVAL_AWARE_28("auto-fast-retrieval-aware-28"),
        RETRIEVAL_AWARE_50("auto-fast-retrieval-aware-50");

        private final String fastReason;

        Profile(String fastReason) {
            this.fastReason = fastReason;
        }

        String fastReason() {
            return fastReason;
        }

        static Profile parse(String raw) {
            if (raw == null || raw.isBlank()) return RETRIEVAL_AWARE_50;
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "Unsupported AUTO router profile: " + raw
                                + ". Expected RETRIEVAL_AWARE_50 or RETRIEVAL_AWARE_28",
                        failure);
            }
        }
    }
}
