package com.yanyue.rag.application.chat.v8;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeDemandClassifier {
    public ConversationalAnswerService.KnowledgeDemand classify(String question) {
        var value = question == null ? "" : question.strip().toLowerCase(Locale.ROOT);
        if (value.matches("^(你好|您好|嗨|hello|hi|hey|谢谢|多谢|在吗|你是谁|你能做什么)[！!。.?？ ]*$")) {
            return ConversationalAnswerService.KnowledgeDemand.NONE;
        }
        if (value.length() <= 80
                && containsAny(value, "你好", "您好", "hello", "hi")
                && containsAny(value, "你是谁", "介绍你", "介绍一下你", "身份", "能做什么", "能提供", "可以帮")) {
            return ConversationalAnswerService.KnowledgeDemand.NONE;
        }
        if (containsAny(value, "我们公司", "本公司", "公司内部", "内部制度", "内部资料", "本项目",
                "我们的项目", "知识库", "部门", "组织", "研发规范", "员工", "权限", "版本发布")) {
            return ConversationalAnswerService.KnowledgeDemand.ORGANIZATION_SPECIFIC;
        }
        return ConversationalAnswerService.KnowledgeDemand.GENERAL;
    }

    private boolean containsAny(String value, String... terms) {
        for (var term : terms) if (value.contains(term)) return true;
        return false;
    }
}
