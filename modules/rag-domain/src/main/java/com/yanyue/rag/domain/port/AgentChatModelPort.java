package com.yanyue.rag.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import com.yanyue.rag.contract.model.ModelProvider;

/**
 * Native chat-completions boundary used by the Agentic RAG runtime.
 *
 * <p>Tool arguments intentionally remain JSON strings. This preserves the provider protocol exactly while
 * allowing the application layer to validate arguments against its own tool schemas before execution.</p>
 */
public interface AgentChatModelPort {
    /** Whether this adapter can serve the provider selected by a Model Profile. */
    default boolean supports(ModelProvider provider) {
        return false;
    }

    AgentChatResponse chat(UUID profileId, AgentChatRequest request, Consumer<AgentChatDelta> onDelta);

    default AgentChatResponse chat(UUID profileId, AgentChatRequest request) {
        return chat(profileId, request, ignored -> { });
    }

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    enum ToolChoiceMode {
        AUTO,
        NONE,
        REQUIRED,
        FUNCTION
    }

    record ToolChoice(ToolChoiceMode mode, String functionName) {
        public ToolChoice {
            mode = Objects.requireNonNull(mode, "mode");
            functionName = normalize(functionName);
            if (mode == ToolChoiceMode.FUNCTION && functionName == null) {
                throw new IllegalArgumentException("functionName is required for a forced function ToolChoice");
            }
            if (mode != ToolChoiceMode.FUNCTION && functionName != null) {
                throw new IllegalArgumentException("functionName is only valid for a forced function ToolChoice");
            }
        }

        public static ToolChoice auto() {
            return new ToolChoice(ToolChoiceMode.AUTO, null);
        }

        public static ToolChoice none() {
            return new ToolChoice(ToolChoiceMode.NONE, null);
        }

        public static ToolChoice required() {
            return new ToolChoice(ToolChoiceMode.REQUIRED, null);
        }

        public static ToolChoice function(String functionName) {
            return new ToolChoice(ToolChoiceMode.FUNCTION, functionName);
        }
    }

    record ToolDefinition(String name, String description, Map<String, Object> parameters) {
        public ToolDefinition {
            name = requireText(name, "Tool name");
            description = requireText(description, "Tool description");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    record ToolCall(
            String id,
            String name,
            String arguments,
            Map<String, Object> providerMetadata
    ) {
        public ToolCall {
            id = requireText(id, "Tool call id");
            name = requireText(name, "Tool call name");
            arguments = arguments == null ? "" : arguments;
            providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        }

        public ToolCall(String id, String name, String arguments) {
            this(id, name, arguments, Map.of());
        }
    }

    record AgentChatMessage(
            Role role,
            String content,
            String reasoningContent,
            List<ToolCall> toolCalls,
            String toolCallId,
            Map<String, Object> providerMetadata
    ) {
        public AgentChatMessage {
            role = Objects.requireNonNull(role, "role");
            content = content == null ? "" : content;
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            toolCallId = normalize(toolCallId);
            providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
            if (role == Role.TOOL && toolCallId == null) {
                throw new IllegalArgumentException("toolCallId is required for TOOL messages");
            }
            if (role != Role.TOOL && toolCallId != null) {
                throw new IllegalArgumentException("toolCallId is only valid for TOOL messages");
            }
            if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls are only valid for ASSISTANT messages");
            }
        }

        public static AgentChatMessage system(String content) {
            return new AgentChatMessage(Role.SYSTEM, content, null, List.of(), null, Map.of());
        }

        public static AgentChatMessage user(String content) {
            return new AgentChatMessage(Role.USER, content, null, List.of(), null, Map.of());
        }

        public static AgentChatMessage assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
            return new AgentChatMessage(Role.ASSISTANT, content, reasoningContent, toolCalls, null, Map.of());
        }

        public static AgentChatMessage tool(String toolCallId, String content) {
            return new AgentChatMessage(Role.TOOL, content, null, List.of(), toolCallId, Map.of());
        }
    }

    record AgentChatRequest(
            List<AgentChatMessage> messages,
            List<ToolDefinition> tools,
            ToolChoice toolChoice,
            Boolean parallelToolCalls,
            Double temperature,
            Integer maxCompletionTokens,
            int timeoutSeconds,
            boolean stream
    ) {
        public AgentChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (messages.isEmpty()) throw new IllegalArgumentException("Agent chat messages cannot be empty");
            if (temperature != null && (temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("temperature must be between 0 and 2");
            }
            if (maxCompletionTokens != null && maxCompletionTokens <= 0) {
                throw new IllegalArgumentException("maxCompletionTokens must be positive");
            }
            if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
            if (tools.isEmpty() && toolChoice != null
                    && toolChoice.mode() != ToolChoiceMode.NONE
                    && toolChoice.mode() != ToolChoiceMode.AUTO) {
                throw new IllegalArgumentException("ToolChoice requires at least one tool");
            }
            if (toolChoice != null && toolChoice.mode() == ToolChoiceMode.FUNCTION
                    && tools.stream().noneMatch(tool -> tool.name().equals(toolChoice.functionName()))) {
                throw new IllegalArgumentException("Forced ToolChoice does not match a declared tool");
            }
        }

        public AgentChatRequest(List<AgentChatMessage> messages, List<ToolDefinition> tools) {
            this(messages, tools, ToolChoice.auto(), false, 0.7, 2_048, 120, true);
        }
    }

    record ToolCallDelta(
            int index,
            String idFragment,
            String nameFragment,
            String argumentsFragment,
            Map<String, Object> providerMetadata
    ) {
        public ToolCallDelta {
            if (index < 0) throw new IllegalArgumentException("Tool call delta index cannot be negative");
            idFragment = idFragment == null ? "" : idFragment;
            nameFragment = nameFragment == null ? "" : nameFragment;
            argumentsFragment = argumentsFragment == null ? "" : argumentsFragment;
            providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        }
    }

    record TokenUsage(
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Map<String, Object> details
    ) {
        public TokenUsage {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    record AgentChatDelta(
            String content,
            String reasoningContent,
            List<ToolCallDelta> toolCalls,
            String finishReason,
            TokenUsage usage,
            Map<String, Object> providerMetadata
    ) {
        public AgentChatDelta {
            content = content == null ? "" : content;
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = normalize(finishReason);
            providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        }
    }

    record AgentChatResponse(
            AgentChatMessage message,
            String finishReason,
            TokenUsage usage,
            Map<String, Object> providerMetadata
    ) {
        public AgentChatResponse {
            message = Objects.requireNonNull(message, "message");
            if (message.role() != Role.ASSISTANT) {
                throw new IllegalArgumentException("Agent chat responses must contain an ASSISTANT message");
            }
            finishReason = normalize(finishReason);
            providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
        }
    }

    private static String requireText(String value, String label) {
        var normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(label + " cannot be blank");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
