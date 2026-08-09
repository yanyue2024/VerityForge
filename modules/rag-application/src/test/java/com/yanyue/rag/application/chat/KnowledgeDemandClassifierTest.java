package com.yanyue.rag.application.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KnowledgeDemandClassifierTest {
    private final KnowledgeDemandClassifier classifier = new KnowledgeDemandClassifier();

    @Test
    void treatsGreetingAsNormalConversation() {
        assertEquals(ConversationalAnswerService.KnowledgeDemand.NONE, classifier.classify("你好"));
        assertEquals(ConversationalAnswerService.KnowledgeDemand.NONE, classifier.classify("你能做什么？"));
        assertEquals(ConversationalAnswerService.KnowledgeDemand.NONE,
                classifier.classify("你好，请简要介绍你的身份和能提供的帮助。"));
    }

    @Test
    void protectsOrganizationSpecificQuestionsFromGuessing() {
        assertEquals(ConversationalAnswerService.KnowledgeDemand.ORGANIZATION_SPECIFIC,
                classifier.classify("我们公司的研发规范是什么？"));
        assertEquals(ConversationalAnswerService.KnowledgeDemand.ORGANIZATION_SPECIFIC,
                classifier.classify("这个知识库里的版本发布要求是什么"));
    }

    @Test
    void allowsGeneralKnowledgeWithAnExplicitBoundary() {
        assertEquals(ConversationalAnswerService.KnowledgeDemand.GENERAL,
                classifier.classify("解释一下向量检索的基本原理"));
    }
}
