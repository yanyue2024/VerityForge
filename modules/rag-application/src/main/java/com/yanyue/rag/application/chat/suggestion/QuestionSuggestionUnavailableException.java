package com.yanyue.rag.application.chat.suggestion;

public class QuestionSuggestionUnavailableException extends RuntimeException {
    public QuestionSuggestionUnavailableException(String message) {
        super(message);
    }

    public QuestionSuggestionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
