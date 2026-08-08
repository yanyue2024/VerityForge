package com.yanyue.rag.api.chat;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.chat.suggestion.QuestionSuggestionService;
import com.yanyue.rag.contract.chat.QuestionSuggestionRequest;
import com.yanyue.rag.contract.chat.QuestionSuggestionResponse;
import com.yanyue.rag.contract.chat.QuestionSuggestionEmptyReason;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/question-suggestions")
public class QuestionSuggestionController {
    private final QuestionSuggestionService suggestions;
    private final QuestionSuggestionCatalogWarmer warmer;

    public QuestionSuggestionController(
            QuestionSuggestionService suggestions,
            QuestionSuggestionCatalogWarmer warmer
    ) {
        this.suggestions = suggestions;
        this.warmer = warmer;
    }

    @PostMapping
    public QuestionSuggestionResponse suggest(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody QuestionSuggestionRequest request
    ) {
        var response = suggestions.suggest(user.organizationId(), user.userId(), request);
        if (response.emptyReason() == QuestionSuggestionEmptyReason.CATALOG_BUILDING) {
            warmer.ensureAvailable(user.organizationId(), user.userId(), request.mode(),
                    request.scope().knowledgeBaseIds());
        }
        return response;
    }
}
