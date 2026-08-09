package com.yanyue.rag.application.chat.deep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Keeps the stable semantic nucleus of a goal in the Deep RAG evidence contract. */
public final class GoalRequirementPolicy {
    private static final Pattern DESCRIPTIVE_GOAL = Pattern.compile(
            "简介|概述|核心定位|能力定位|定位是什么|是什么|定义|描述|作用|用途|应用场景|原因|欢迎|介绍|组成|关系|原理|功能"
    );
    private static final Pattern TRAILING_ANSWER_TEMPLATE = Pattern.compile(
            "(?:[，,；;]|的)?\\s*(?:(?:资料(?:中)?(?:说明了|列出了)?哪些?)|包括)?\\s*"
                    + "前置条件.*[？?]?$");

    private GoalRequirementPolicy() {
    }

    public static List<Draft> normalize(
            String question,
            String declaredGoalType,
            List<Draft> proposed
    ) {
        var unique = new LinkedHashMap<String, Draft>();
        for (var draft : proposed) unique.putIfAbsent(draft.key(), draft);
        var core = unique.remove("core");
        if (core != null) {
            var result = new ArrayList<Draft>();
            result.add(core);
            unique.values().stream().limit(2).forEach(result::add);
            return List.copyOf(result);
        }

        boolean descriptive = isDescriptive(question, declaredGoalType);
        var synthesized = new Draft("core", coreDescription(question, descriptive));
        if (descriptive) return List.of(synthesized);

        var result = new ArrayList<Draft>();
        result.add(synthesized);
        unique.values().stream().limit(2).forEach(result::add);
        return List.copyOf(result);
    }

    static boolean isDescriptive(String question, String declaredGoalType) {
        var type = declaredGoalType == null ? "" : declaredGoalType.strip().toUpperCase(Locale.ROOT);
        return type.equals("DESCRIPTIVE") || DESCRIPTIVE_GOAL.matcher(question == null ? "" : question).find();
    }

    private static String coreDescription(String question, boolean descriptive) {
        var value = question == null || question.isBlank() ? "该子问题" : question.strip();
        if (descriptive) {
            value = TRAILING_ANSWER_TEMPLATE.matcher(value).replaceFirst("").strip();
            if (value.endsWith("的")) value = value.substring(0, value.length() - 1).strip();
        }
        var prefix = descriptive
                ? "直接说明该主题本身的定义、作用、组成或主要内容："
                : "直接说明该操作目标的实际做法、主要过程或核心事实：";
        var result = prefix + value;
        return result.substring(0, Math.min(500, result.length()));
    }

    public record Draft(String key, String description) {
        public Draft {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Requirement key 不能为空");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Requirement description 不能为空");
            }
            key = key.strip();
            description = description.strip();
        }
    }
}
