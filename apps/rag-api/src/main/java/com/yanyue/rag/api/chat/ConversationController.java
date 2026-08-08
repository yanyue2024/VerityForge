package com.yanyue.rag.api.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.contract.chat.ConversationPage;
import com.yanyue.rag.contract.chat.ConversationSettings;
import com.yanyue.rag.contract.chat.ConversationView;
import com.yanyue.rag.contract.chat.CreateConversationRequest;
import com.yanyue.rag.contract.chat.UpdateConversationRequest;
import com.yanyue.rag.application.pipeline.AssistantProfileService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 50;
    private static final TypeReference<List<MessageCitationGoal>> MESSAGE_CITATION_GOALS = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final AssistantProfileService assistantProfiles;

    public ConversationController(DSLContext dsl, ObjectMapper objectMapper,
                                  AssistantProfileService assistantProfiles) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.assistantProfiles = assistantProfiles;
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "30") int limit,
                                 @RequestParam(required = false) String query) {
        var pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        var decoded = decodeCursor(cursor);
        var search = query == null || query.isBlank() ? null : "%" + query.strip() + "%";
        var records = dsl.fetch("""
                SELECT conversation.id, conversation.title, conversation.settings,
                       conversation.pinned_at, conversation.created_at, conversation.updated_at,
                       CASE WHEN conversation.pinned_at IS NULL THEN 1 ELSE 0 END AS pin_rank,
                       COALESCE(conversation.pinned_at, conversation.updated_at) AS sort_time
                FROM conversation
                WHERE conversation.organization_id = ? AND conversation.created_by = ?
                  AND conversation.conversation_kind = 'USER' AND conversation.deleted_at IS NULL
                  AND (
                    ?::text IS NULL
                    OR conversation.title ILIKE ?::text
                    OR EXISTS (
                      SELECT 1
                      FROM conversation_message message
                      WHERE message.conversation_id = conversation.id
                        AND message.role = 'user'
                        AND message.content ILIKE ?::text
                    )
                  )
                  AND (
                    ?::integer IS NULL
                    OR CASE WHEN pinned_at IS NULL THEN 1 ELSE 0 END > ?::integer
                    OR (
                      CASE WHEN pinned_at IS NULL THEN 1 ELSE 0 END = ?::integer
                      AND COALESCE(pinned_at, updated_at) < ?::timestamptz
                    )
                    OR (
                      CASE WHEN pinned_at IS NULL THEN 1 ELSE 0 END = ?::integer
                      AND COALESCE(pinned_at, updated_at) = ?::timestamptz
                      AND id < ?::uuid
                    )
                  )
                ORDER BY pin_rank ASC, sort_time DESC, id DESC
                LIMIT ?
                """, user.organizationId(), user.userId(), search, search, search,
                decoded == null ? null : decoded.pinRank(),
                decoded == null ? null : decoded.pinRank(),
                decoded == null ? null : decoded.pinRank(),
                decoded == null ? null : decoded.sortTime(),
                decoded == null ? null : decoded.pinRank(),
                decoded == null ? null : decoded.sortTime(),
                decoded == null ? null : decoded.id(), pageSize + 1);

        var hasNext = records.size() > pageSize;
        var visible = records.subList(0, Math.min(records.size(), pageSize));
        var items = visible.stream().map(this::toView).toList();
        var nextCursor = hasNext ? encodeCursor(visible.getLast()) : null;
        return new ConversationPage(items, nextCursor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestBody(required = false) CreateConversationRequest request) {
        var id = UUID.randomUUID();
        var title = request == null || request.title() == null || request.title().isBlank()
                ? "新对话" : request.title().strip();
        var settings = request == null || request.settings() == null
                ? ConversationSettings.defaults() : request.settings();
        var assistantProfile = assistantProfiles.published(user.organizationId());
        var record = dsl.fetchOne("""
                INSERT INTO conversation (id, organization_id, title, created_by, settings,
                                          assistant_profile_version_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, title, settings, pinned_at, created_at, updated_at
                """, id, user.organizationId(), title, user.userId(), toJson(settings), assistantProfile.id());
        return toView(record);
    }

    @GetMapping("/{conversationId}")
    public ConversationView get(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable UUID conversationId) {
        return toView(requireOwnedConversation(user, conversationId));
    }

    @PatchMapping("/{conversationId}")
    public ConversationView update(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID conversationId,
                                   @Valid @RequestBody UpdateConversationRequest request) {
        var current = requireOwnedConversation(user, conversationId);
        var title = request.title() == null ? current.get("title", String.class) : request.title().strip();
        if (title.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题不能为空");
        var settings = request.settings() == null
                ? current.get("settings", JSONB.class).data() : toJson(request.settings());
        var pinnedAt = request.pinned() == null
                ? current.get("pinned_at", OffsetDateTime.class)
                : Boolean.TRUE.equals(request.pinned()) ? OffsetDateTime.now() : null;
        var updated = dsl.fetchOne("""
                UPDATE conversation
                SET title = ?, settings = ?::jsonb, pinned_at = ?::timestamptz, updated_at = now()
                WHERE id = ? AND organization_id = ? AND created_by = ?
                  AND conversation_kind = 'USER' AND deleted_at IS NULL
                RETURNING id, title, settings, pinned_at, created_at, updated_at
                """, title, settings, pinnedAt, conversationId, user.organizationId(), user.userId());
        return toView(updated);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID conversationId) {
        var changed = dsl.execute("""
                UPDATE conversation SET deleted_at = now(), pinned_at = NULL, updated_at = now()
                WHERE id = ? AND organization_id = ? AND created_by = ?
                  AND conversation_kind = 'USER' AND deleted_at IS NULL
                """, conversationId, user.organizationId(), user.userId());
        if (changed == 0) throw notFound();
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable UUID conversationId) {
        requireOwnedConversation(user, conversationId);
        return dsl.fetch("""
                SELECT m.id, m.role, m.content,
                       COALESCE(
                         CASE WHEN m.role = 'user' THEN turn.active_run_id::text END,
                         m.run_id::text,
                         m.metadata ->> 'runId'
                       ) AS run_id,
                       m.created_at, r.status AS run_status, r.requested_mode, r.selected_mode, r.answer_mode,
                       r.retrieval_health, r.evidence_count,
                       CASE WHEN r.started_at IS NOT NULL
                                  AND COALESCE(generation.started_at, r.completed_at) IS NOT NULL
                            THEN greatest(0, (extract(epoch FROM (
                                COALESCE(generation.started_at, r.completed_at) - r.started_at
                            )) * 1000)::bigint) END AS latency_ms,
                       ap.assistant_name, ap.version AS assistant_profile_version,
                       EXISTS (
                           SELECT 1 FROM rag_run_event trace_event
                           WHERE trace_event.run_id = r.id
                             AND trace_event.event_type IN (
                               'QUERY_REWRITE_STARTED', 'QUERY_REWRITTEN', 'RETRIEVAL_STARTED',
                               'RETRIEVAL_RESULT', 'RERANK_COMPLETED', 'RERANK_SKIPPED',
                               'PLAN_CREATED', 'GOAL_RESEARCH_STARTED', 'EVIDENCE_JUDGE_STARTED',
                               'ANSWER_GENERATION_STARTED', 'ANSWER_MODE_SELECTED'
                             )
                       ) AS trace_available,
                       (m.role = 'assistant' AND r.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                        AND (turn.id IS NULL OR turn.active_run_id = r.id)) AS reprocessable
                FROM conversation_message m
                LEFT JOIN conversation_turn turn ON turn.id = m.turn_id
                LEFT JOIN rag_run r ON r.id = COALESCE(
                    CASE WHEN m.role = 'user' THEN turn.active_run_id END,
                    m.run_id,
                    (m.metadata ->> 'runId')::uuid
                )
                LEFT JOIN LATERAL (
                    SELECT min(generation_event.created_at) AS started_at
                    FROM rag_run_event generation_event
                    WHERE generation_event.run_id = r.id
                      AND generation_event.event_type IN (
                        'ANSWER_MODE_SELECTED', 'ANSWER_GENERATION_STARTED', 'ANSWER_DELTA'
                      )
                ) generation ON TRUE
                LEFT JOIN assistant_profile_version ap ON ap.id = r.assistant_profile_version_id
                WHERE m.conversation_id = ?
                  AND (
                    m.turn_id IS NULL
                    OR m.role NOT IN ('user', 'assistant')
                    OR m.role = 'user'
                    OR (m.role = 'assistant' AND m.run_id = turn.active_run_id)
                  )
                ORDER BY COALESCE(turn.created_at, m.created_at),
                         CASE WHEN m.role = 'user' THEN 0 ELSE 1 END,
                         m.created_at
                """, conversationId).map(record -> {
                    var role = record.get("role", String.class);
                    var runId = record.get("run_id", String.class);
                    var restricted = "assistant".equals(role) && hasRestrictedCitation(runId, user.userId());
                    return new MessageView(
                            record.get("id", UUID.class), role,
                            restricted ? "该回答引用的文档权限已发生变化，当前无法显示原回答。"
                                    : record.get("content", String.class),
                            "assistant".equals(role) && !restricted ? citations(runId, user.userId()) : List.of(),
                            restricted,
                            runId(runId),
                            Boolean.TRUE.equals(record.get("trace_available", Boolean.class)),
                            Boolean.TRUE.equals(record.get("reprocessable", Boolean.class)),
                            record.get("run_status", String.class),
                            record.get("requested_mode", String.class),
                            record.get("selected_mode", String.class),
                            record.get("answer_mode", String.class),
                            record.get("retrieval_health", String.class),
                            record.get("evidence_count", Integer.class),
                            record.get("latency_ms", Long.class),
                            record.get("assistant_name", String.class),
                            record.get("assistant_profile_version", Integer.class),
                            record.get("created_at", OffsetDateTime.class).toInstant()
                    );
                });
    }

    private Record requireOwnedConversation(AuthenticatedUser user, UUID conversationId) {
        return dsl.fetchOptional("""
                SELECT id, title, settings, pinned_at, created_at, updated_at
                FROM conversation
                WHERE id = ? AND organization_id = ? AND created_by = ?
                  AND conversation_kind = 'USER' AND deleted_at IS NULL
                """, conversationId, user.organizationId(), user.userId()).orElseThrow(this::notFound);
    }

    private ConversationView toView(Record record) {
        var pinnedAt = record.get("pinned_at", OffsetDateTime.class);
        return new ConversationView(
                record.get("id", UUID.class),
                record.get("title", String.class),
                fromJson(record.get("settings", JSONB.class)),
                pinnedAt != null,
                pinnedAt == null ? null : pinnedAt.toInstant(),
                record.get("created_at", OffsetDateTime.class).toInstant(),
                record.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private String toJson(ConversationSettings value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid conversation settings", exception);
        }
    }

    private ConversationSettings fromJson(JSONB value) {
        if (value == null || value.data() == null || value.data().isBlank()) {
            return ConversationSettings.defaults();
        }
        try {
            return objectMapper.readValue(value.data(), ConversationSettings.class);
        } catch (JsonProcessingException exception) {
            return ConversationSettings.defaults();
        }
    }

    private String encodeCursor(Record record) {
        var raw = record.get("pin_rank", Integer.class) + "|"
                + record.get("sort_time", OffsetDateTime.class).toInstant() + "|"
                + record.get("id", UUID.class);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            var parts = raw.split("\\|", 3);
            return new Cursor(Integer.parseInt(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的会话游标");
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
    }

    private List<MessageCitation> citations(String runId, UUID userId) {
        var value = runId(runId);
        if (value == null) return List.of();
        var saved = dsl.fetch("""
                SELECT c.citation_index, c.chunk_id, c.document_id, c.document_version_id,
                       d.title AS document_title, kb.name AS knowledge_base_name,
                       c.quote_text, c.source_start, c.source_end, d.updated_at AS document_updated_at,
                       COALESCE((
                         SELECT jsonb_agg(
                                  jsonb_build_object(
                                    'goalId', association.goal_id,
                                    'goalQuestion', association.goal_question,
                                    'recalledChildChunkIds', association.recalled_child_chunk_ids
                                  )
                                  ORDER BY association.goal_order, association.goal_id
                                )
                         FROM (
                           SELECT e.sub_question_id AS goal_id,
                                  COALESCE(min(gc.goal_order), 2147483647) AS goal_order,
                                  COALESCE((
                                    SELECT goal.value ->> 'question'
                                    FROM rag_run_event event
                                    CROSS JOIN LATERAL jsonb_array_elements(
                                      CASE
                                        WHEN jsonb_typeof(event.payload -> 'goals') = 'array'
                                          THEN event.payload -> 'goals'
                                        ELSE '[]'::jsonb
                                      END
                                    ) AS goal(value)
                                    WHERE event.run_id = e.run_id
                                      AND event.event_type = 'PLAN_CREATED'
                                      AND goal.value ->> 'id' = e.sub_question_id::text
                                    ORDER BY event.sequence DESC
                                    LIMIT 1
                                  ), '未命名目标') AS goal_question,
                                  COALESCE((
                                    SELECT jsonb_agg(child.chunk_id ORDER BY child.best_rank, child.chunk_id)
                                    FROM (
                                      SELECT candidate.chunk_id,
                                             min(COALESCE(candidate.rerank_rank, candidate.rrf_rank)) AS best_rank
                                      FROM agent_goal_ranked_candidate candidate
                                      JOIN chunk recalled_child
                                        ON recalled_child.id = candidate.chunk_id
                                       AND recalled_child.parent_chunk_id = c.chunk_id
                                       AND recalled_child.chunk_type = 'CHILD'
                                      WHERE candidate.run_id = e.run_id
                                        AND candidate.goal_id = e.sub_question_id
                                        AND candidate.selected_for_parent
                                      GROUP BY candidate.chunk_id
                                    ) child
                                  ), '[]'::jsonb) AS recalled_child_chunk_ids
                           FROM evidence_item e
                           LEFT JOIN agent_goal_ranked_candidate gc
                             ON gc.run_id = e.run_id
                            AND gc.goal_id = e.sub_question_id
                           WHERE e.run_id = c.run_id
                             AND e.parent_chunk_id = c.chunk_id
                             AND e.sub_question_id IS NOT NULL
                           GROUP BY e.run_id, e.sub_question_id
                         ) association
                       ), '[]'::jsonb) AS goal_associations,
                       (SELECT min(db.page_number)
                        FROM chunk source_chunk
                        JOIN document_block db ON db.id = ANY(source_chunk.source_block_ids)
                        WHERE source_chunk.id = c.chunk_id) AS page_number
                FROM citation c
                JOIN document d ON d.id = c.document_id
                JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
                WHERE c.run_id = ? AND document_is_accessible(c.document_id, ?)
                ORDER BY c.citation_index
                """, value, userId).map(this::messageCitation);
        if (!saved.isEmpty()) return saved;

        return dsl.fetch("""
                SELECT row_number() OVER (
                           ORDER BY rc.rerank_score DESC NULLS LAST,
                                    rc.rrf_score DESC NULLS LAST,
                                    rc.created_at,
                                    source_chunk.order_index
                       )::integer AS citation_index,
                       source_chunk.id AS chunk_id,
                       d.id AS document_id,
                       dv.id AS document_version_id,
                       d.title AS document_title,
                       kb.name AS knowledge_base_name,
                       source_chunk.chunk_text AS quote_text,
                       NULL::integer AS source_start,
                       NULL::integer AS source_end,
                       d.updated_at AS document_updated_at,
                       '[]'::jsonb AS goal_associations,
                       (SELECT min(db.page_number)
                        FROM document_block db
                        WHERE db.id = ANY(source_chunk.source_block_ids)) AS page_number
                FROM retrieval_candidate rc
                JOIN rag_run r ON r.id = rc.run_id
                JOIN chunk source_chunk ON source_chunk.id = rc.chunk_id
                JOIN document_version dv ON dv.id = source_chunk.document_version_id
                JOIN document d ON d.id = dv.document_id
                JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
                WHERE rc.run_id = ?
                  AND rc.accepted_context
                  AND r.selected_mode = 'FAST'
                  AND r.answer_mode IN ('GROUNDED', 'ANSWER_WITH_EVIDENCE', 'PARTIAL_GROUNDED')
                  AND document_is_accessible(d.id, ?)
                ORDER BY rc.rerank_score DESC NULLS LAST,
                         rc.rrf_score DESC NULLS LAST,
                         rc.created_at,
                         source_chunk.order_index
                LIMIT (SELECT greatest(coalesce(evidence_count, 0), 0) FROM rag_run WHERE id = ?)
                """, value, userId, value).map(this::messageCitation);
    }

    private MessageCitation messageCitation(Record record) {
        return new MessageCitation(
                record.get("citation_index", Integer.class),
                record.get("chunk_id", UUID.class),
                record.get("document_id", UUID.class),
                record.get("document_version_id", UUID.class),
                record.get("document_title", String.class),
                record.get("knowledge_base_name", String.class),
                record.get("quote_text", String.class),
                record.get("page_number", Integer.class),
                record.get("source_start", Integer.class),
                record.get("source_end", Integer.class),
                record.get("document_updated_at", OffsetDateTime.class).toInstant(),
                messageCitationGoals(record)
        );
    }

    private List<MessageCitationGoal> messageCitationGoals(Record record) {
        var value = record.get("goal_associations", JSONB.class);
        if (value == null || value.data() == null || value.data().isBlank()) return List.of();
        try {
            return objectMapper.readValue(value.data(), MESSAGE_CITATION_GOALS);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private boolean hasRestrictedCitation(String runId, UUID userId) {
        var value = runId(runId);
        if (value == null) return false;
        return dsl.fetchOptional("""
                SELECT 1 FROM citation c
                WHERE c.run_id = ? AND NOT document_is_accessible(c.document_id, ?)
                """, value, userId).isPresent();
    }

    private UUID runId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record Cursor(int pinRank, Instant sortTime, UUID id) {
    }

    public record MessageView(UUID id, String role, String content, List<MessageCitation> citations,
                              boolean restricted, UUID runId, boolean traceAvailable, boolean reprocessable,
                              String runStatus,
                              String requestedMode, String selectedMode,
                              String answerMode, String retrievalHealth, Integer evidenceCount,
                              Long latencyMs, String assistantName, Integer assistantProfileVersion,
                              Instant createdAt) {
    }

    public record MessageCitation(Integer index, UUID chunkId, UUID documentId, UUID documentVersionId,
                                  String documentTitle, String knowledgeBaseName, String quote,
                                  Integer pageNumber, Integer sourceStart, Integer sourceEnd,
                                  Instant documentUpdatedAt, List<MessageCitationGoal> goalAssociations) {
    }

    public record MessageCitationGoal(UUID goalId, String goalQuestion, List<UUID> recalledChildChunkIds) {
    }
}
