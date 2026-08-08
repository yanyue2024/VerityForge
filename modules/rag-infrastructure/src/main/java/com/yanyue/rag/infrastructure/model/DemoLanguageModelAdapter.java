package com.yanyue.rag.infrastructure.model;

import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.AgentChatModelPort;
import com.yanyue.rag.domain.port.LanguageModelPort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemoLanguageModelAdapter implements LanguageModelPort, AgentChatModelPort {
    private final ModelProfileRepository profiles;
    private final UUID activeProfileId;

    public DemoLanguageModelAdapter(
            ModelProfileRepository profiles,
            @Value("${rag.models.active-language-model-profile-id:}") String activeProfileId
    ) {
        this.profiles = profiles;
        this.activeProfileId = activeProfileId == null || activeProfileId.isBlank()
                ? null
                : UUID.fromString(activeProfileId.strip());
    }

    @Override
    public boolean supports(ModelProvider provider) {
        return provider == ModelProvider.DEMO;
    }

    @Override
    public String rewriteQuery(String query, List<String> recentMessages) {
        requireActiveDemoProfile();
        if (recentMessages.isEmpty()) return query;
        var lastUserContext = recentMessages.reversed().stream()
                .filter(message -> message.startsWith("user:"))
                .findFirst().orElse(recentMessages.getLast());
        return query + "（上下文：" + truncate(lastUserContext.replaceFirst("^[^:]+:\\s*", ""), 100) + "）";
    }

    @Override
    public List<RetrievalHit> rerank(String query, List<RetrievalHit> candidates, int topK) {
        requireActiveDemoProfile();
        var queryTerms = terms(query);
        var ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble((RetrievalHit hit) -> lexicalScore(queryTerms, hit.text()) + hit.score()).reversed());
        return ranked.stream().limit(topK).toList();
    }

    @Override
    public String generate(String query, List<RetrievalHit> context) {
        requireActiveDemoProfile();
        if (context.isEmpty()) {
            return "当前检索范围内没有找到足够的有效文档证据。我没有使用长期记忆或过期内容补写结论，请调整知识范围、Metadata 过滤条件，或先完成文档索引。";
        }
        var answer = new StringBuilder();
        answer.append("根据当前已发布且在有效期内的知识内容，针对“").append(query).append("”，可以归纳为：\n\n");
        for (int index = 0; index < Math.min(4, context.size()); index++) {
            var hit = context.get(index);
            answer.append(index + 1).append(". ").append(truncate(hit.text().replaceAll("\\s+", " "), 220))
                    .append(" [").append(index + 1).append("]\n");
        }
        answer.append("\n以上结论仅由本次接受的证据生成；引用可在右侧证据抽屉中核对原文与文档版本。");
        return answer.toString();
    }

    @Override
    public AgentChatResponse chat(
            UUID profileId,
            AgentChatRequest request,
            java.util.function.Consumer<AgentChatDelta> onDelta
    ) {
        var profile = requireDemoProfile(profileId);
        var consumer = onDelta == null
                ? (java.util.function.Consumer<AgentChatDelta>) ignored -> { }
                : onDelta;
        var toolResults = request.messages().stream().filter(message -> message.role() == Role.TOOL).toList();
        var metadata = Map.<String, Object>of(
                "mode", "demo",
                "model", profile.modelName(),
                "deterministic", true
        );
        if (toolResults.isEmpty() && !request.tools().isEmpty()
                && (request.toolChoice() == null || request.toolChoice().mode() != ToolChoiceMode.NONE)) {
            var tool = selectTool(request);
            var call = new ToolCall("demo-call-1", tool.name(), demoArguments(tool.name(), lastUser(request)));
            var message = new AgentChatMessage(Role.ASSISTANT, "", "需要先查询当前知识范围。",
                    List.of(call), null, metadata);
            if (request.stream()) {
                consumer.accept(new AgentChatDelta("", message.reasoningContent(), List.of(
                        new ToolCallDelta(0, call.id(), call.name(), call.arguments(), Map.of("type", "function"))
                ), "tool_calls", demoUsage(request, 24), metadata));
            }
            return new AgentChatResponse(message, "tool_calls", demoUsage(request, 24), metadata);
        }

        var lastToolOutput = toolResults.isEmpty() ? "未配置可调用的知识工具" : toolResults.getLast().content();
        var content = "Demo Agent 已完成知识工具调用。工具返回摘要："
                + truncate(lastToolOutput.replaceAll("\\s+", " "), 600);
        var message = new AgentChatMessage(Role.ASSISTANT, content, "", List.of(), null, metadata);
        if (request.stream()) {
            consumer.accept(new AgentChatDelta(content, "", List.of(), "stop",
                    demoUsage(request, content.length()), metadata));
        }
        return new AgentChatResponse(message, "stop", demoUsage(request, content.length()), metadata);
    }

    private ToolDefinition selectTool(AgentChatRequest request) {
        if (request.toolChoice() != null && request.toolChoice().mode() == ToolChoiceMode.FUNCTION) {
            return request.tools().stream()
                    .filter(tool -> tool.name().equals(request.toolChoice().functionName()))
                    .findFirst().orElseThrow();
        }
        return request.tools().stream().filter(tool -> "knowledge_search".equals(tool.name()))
                .findFirst().orElse(request.tools().getFirst());
    }

    private String lastUser(AgentChatRequest request) {
        return request.messages().reversed().stream()
                .filter(message -> message.role() == Role.USER)
                .map(AgentChatMessage::content)
                .findFirst().orElse("知识库查询");
    }

    private String demoArguments(String toolName, String query) {
        var escaped = jsonString(query);
        return switch (toolName) {
            case "knowledge_search" -> "{\"queries\":[" + escaped + "]}";
            case "grep_chunks" -> "{\"query\":" + escaped + "}";
            case "list_knowledge_chunks" -> "{\"knowledge_id\":\"demo\",\"offset\":0,\"limit\":20}";
            case "get_document_info" -> "{\"knowledge_ids\":[\"demo\"]}";
            default -> "{}";
        };
    }

    private String jsonString(String value) {
        var escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private TokenUsage demoUsage(AgentChatRequest request, int outputCharacters) {
        var inputCharacters = request.messages().stream().mapToInt(message -> message.content().length()).sum();
        var inputTokens = Math.max(1, (inputCharacters + 3) / 4);
        var outputTokens = Math.max(1, (outputCharacters + 3) / 4);
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens,
                Map.of("estimated", true));
    }

    private void requireActiveDemoProfile() {
        if (activeProfileId == null) {
            throw new IllegalStateException("No explicit demo language model profile is configured");
        }
        requireDemoProfile(activeProfileId);
    }

    private ModelProfile requireDemoProfile(UUID profileId) {
        var profile = profiles.findById(profileId)
                .orElseThrow(() -> new IllegalStateException("Configured demo language model profile was not found"));
        if (!profile.enabled()
                || profile.provider() != ModelProvider.DEMO
                || profile.profileType() != ModelProfileType.CHAT) {
            throw new IllegalStateException("Configured language model profile is not an enabled CHAT demo profile");
        }
        return profile;
    }

    private double lexicalScore(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty()) return 0;
        var textTerms = terms(text);
        long overlap = queryTerms.stream().filter(textTerms::contains).count();
        return (double) overlap / queryTerms.size();
    }

    private Set<String> terms(String value) {
        var terms = new HashSet<String>();
        for (var part : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!part.isBlank()) terms.add(part);
        }
        return terms;
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }
}
