package com.yanyue.rag.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoLanguageModelAdapterTest {
    @Test
    void rejectsMissingExplicitProfile() {
        var adapter = new DemoLanguageModelAdapter(mock(ModelProfileRepository.class), "");

        assertThrows(IllegalStateException.class, () -> adapter.generate("question", List.of()));
    }

    @Test
    void rejectsDisabledDemoProfileInsteadOfFallingBack() {
        var profileId = UUID.randomUUID();
        var repository = mock(ModelProfileRepository.class);
        when(repository.findById(profileId)).thenReturn(Optional.of(profile(profileId, false)));
        var adapter = new DemoLanguageModelAdapter(repository, profileId.toString());

        assertThrows(IllegalStateException.class, () -> adapter.generate("question", List.of()));
    }

    @Test
    void runsOnlyWithEnabledChatDemoProfile() {
        var profileId = UUID.randomUUID();
        var repository = mock(ModelProfileRepository.class);
        when(repository.findById(profileId)).thenReturn(Optional.of(profile(profileId, true)));
        var adapter = new DemoLanguageModelAdapter(repository, profileId.toString());

        assertTrue(adapter.generate("question", List.of()).contains("没有找到足够"));
    }

    @Test
    void performsDeterministicToolRoundThenAcceptsToolResult() {
        var profileId = UUID.randomUUID();
        var repository = mock(ModelProfileRepository.class);
        when(repository.findById(profileId)).thenReturn(Optional.of(profile(profileId, true)));
        var adapter = new DemoLanguageModelAdapter(repository, profileId.toString());
        var tool = new AgentChatModelPort.ToolDefinition("knowledge_search", "search", Map.of(
                "type", "object", "properties", Map.of()
        ));
        var firstRequest = new AgentChatModelPort.AgentChatRequest(
                List.of(AgentChatModelPort.AgentChatMessage.user("员工休假政策")), List.of(tool));

        var first = adapter.chat(profileId, firstRequest);

        assertEquals("tool_calls", first.finishReason());
        assertEquals("knowledge_search", first.message().toolCalls().getFirst().name());
        assertTrue(first.message().toolCalls().getFirst().arguments().contains("员工休假政策"));

        var call = first.message().toolCalls().getFirst();
        var secondRequest = new AgentChatModelPort.AgentChatRequest(
                List.of(
                        AgentChatModelPort.AgentChatMessage.user("员工休假政策"),
                        first.message(),
                        AgentChatModelPort.AgentChatMessage.tool(call.id(),
                                "{\"success\":true,\"output\":\"年假为十天\"}")
                ), List.of(tool));
        var second = adapter.chat(profileId, secondRequest);

        assertEquals("stop", second.finishReason());
        assertTrue(second.message().toolCalls().isEmpty());
        assertTrue(second.message().content().contains("年假为十天"));
        assertEquals(true, second.providerMetadata().get("deterministic"));
    }

    private ModelProfile profile(UUID id, boolean enabled) {
        var now = Instant.parse("2026-07-13T00:00:00Z");
        return new ModelProfile(id, UUID.randomUUID(), ModelProfileType.CHAT, ModelProvider.DEMO,
                "Demo", "deterministic-demo-v1", null, null, Map.of(), enabled,
                ModelProfileTestStatus.NOT_TESTED, null, null, Map.of(), now, now);
    }
}
