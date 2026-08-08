package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.chat.CreateRunRequest;
import com.yanyue.rag.contract.chat.KnowledgeScope;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.model.PipelineConfig;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.AgentKnowledgeToolPort;
import com.yanyue.rag.domain.port.AgentReactPersistencePort;
import com.yanyue.rag.domain.port.CitationPort;
import com.yanyue.rag.domain.port.CitationValidationPort;
import com.yanyue.rag.domain.port.ConversationMemoryPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.RerankModelPort;
import com.yanyue.rag.domain.port.RetrievalHit;
import com.yanyue.rag.domain.port.RetrievalPort;
import com.yanyue.rag.domain.port.RunRecordPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReactAgentEngineTest {
    @Test
    void retrievalOnlyUsesNativeToolPairsAndNeverPersistsAnAnswer() {
        var now = Instant.parse("2026-07-19T00:00:00Z");
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var conversationId = UUID.randomUUID();
        var profileId = UUID.randomUUID();
        var rerankProfileId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var chunkId = UUID.randomUUID();
        var requests = new ArrayList<AgentChatModelPort.AgentChatRequest>();
        AgentChatModelPort model = new ScriptedModel(requests);

        var profiles = mock(ModelProfileRepository.class);
        when(profiles.findById(profileId)).thenReturn(Optional.of(new ModelProfile(
                profileId, organizationId, ModelProfileType.CHAT, ModelProvider.OPENAI_COMPATIBLE,
                "GPT", "gpt-test", "https://example.test/v1", "key", Map.of(), true,
                ModelProfileTestStatus.PASSED, now, "ok", Map.of("toolCalling", true), now, now)));
        var pipelineConfigs = mock(PipelineConfigService.class);
        when(pipelineConfigs.resolve(organizationId, profileId)).thenReturn(new PipelineConfig(
                UUID.randomUUID(), organizationId, "react", "agentic-react-v1",
                "weknora-progressive-rag-v1", profileId, profileId, rerankProfileId,
                30, 30, 40, 20, 8, 8_000, 0.05, 120, true, now, now));
        var metadata = mock(MetadataSchemaService.class);
        when(metadata.validateFilters(eq(organizationId), eq(List.of(knowledgeBaseId)), eq(List.of())))
                .thenReturn(List.of());
        var retrieval = mock(RetrievalPort.class);
        var hit = new RetrievalHit(chunkId, null, documentId, versionId, "Policy", "Evidence text",
                0.9, List.of("keyword"));
        when(retrieval.keywordSearch(eq("policy"), any(), eq(30))).thenReturn(List.of(hit));
        when(retrieval.semanticSearch(eq("policy"), any(), eq(30), eq(4))).thenReturn(List.of(hit));
        var knowledgeTools = mock(AgentKnowledgeToolPort.class);
        when(knowledgeTools.getDocumentInfo(eq(List.of(documentId)), any())).thenReturn(List.of(
                new AgentKnowledgeToolPort.DocumentInfo(knowledgeBaseId, documentId, versionId,
                        "Policy", "policy.md", "MARKDOWN", "1", "owner", "ops", List.of(), Map.of())));
        var rerank = mock(RerankModelPort.class);
        when(rerank.rerank(eq(rerankProfileId), eq("policy"), any(), anyInt()))
                .thenReturn(List.of(new RerankModelPort.RerankScore(0, 0.95)));
        var persistence = mock(AgentReactPersistencePort.class);
        when(persistence.loadCheckpoint(runId)).thenReturn(Optional.empty());
        var memory = mock(ConversationMemoryPort.class);
        when(memory.recentMessages(conversationId, 5)).thenReturn(List.of());
        var records = mock(RunRecordPort.class);
        when(records.isCancellationRequested(runId)).thenReturn(false);

        var engine = new ReactAgentEngine(new ObjectMapper(), List.of(model), profiles, retrieval,
                knowledgeTools, rerank, pipelineConfigs, metadata, persistence, memory, records,
                mock(RunEventHub.class), mock(CitationValidationPort.class), mock(CitationPort.class),
                Clock.fixed(now, ZoneOffset.UTC));
        var result = engine.execute(runId, conversationId, organizationId, userId,
                new CreateRunRequest("policy", RunMode.DEEP,
                        new KnowledgeScope(List.of(knowledgeBaseId), List.of()), List.of(), profileId), false);

        assertEquals("", result);
        assertEquals(2, requests.size());
        assertEquals(4, requests.getFirst().tools().size());
        var secondMessages = requests.getLast().messages();
        assertTrue(secondMessages.stream().anyMatch(message -> message.role() == AgentChatModelPort.Role.ASSISTANT
                && message.toolCalls().stream().anyMatch(call -> call.id().equals("call-1"))));
        assertTrue(secondMessages.stream().anyMatch(message -> message.role() == AgentChatModelPort.Role.TOOL
                && "call-1".equals(message.toolCallId())));
        verify(persistence).saveKnowledgeReference(any());
        verify(memory, never()).append(any(), any(), any(), any());
    }

    private static final class ScriptedModel implements AgentChatModelPort {
        private final List<AgentChatRequest> requests;
        private int call;

        private ScriptedModel(List<AgentChatRequest> requests) {
            this.requests = requests;
        }

        @Override
        public boolean supports(ModelProvider provider) {
            return provider == ModelProvider.OPENAI_COMPATIBLE;
        }

        @Override
        public AgentChatResponse chat(UUID profileId, AgentChatRequest request, Consumer<AgentChatDelta> onDelta) {
            requests.add(request);
            if (call++ == 0) {
                return new AgentChatResponse(AgentChatMessage.assistant("", "",
                        List.of(new ToolCall("call-1", "knowledge_search", "{\"queries\":[\"policy\"]}"))),
                        "tool_calls", new TokenUsage(10, 2, 12, Map.of()), Map.of("model", "test"));
            }
            return new AgentChatResponse(AgentChatMessage.assistant("retrieval complete", "", List.of()),
                    "stop", new TokenUsage(20, 2, 22, Map.of()), Map.of("model", "test"));
        }
    }
}
