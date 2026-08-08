package com.yanyue.rag.application.chat.v7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoalRequirementPolicyV7Test {
    @Test
    void descriptiveGoalWithoutCoreDropsMechanicalAnswerTemplateFacets() {
        var proposed = List.of(
                new GoalRequirementPolicyV7.Draft("r1", "OpenStack 简介中的前置条件"),
                new GoalRequirementPolicyV7.Draft("r2", "OpenStack 简介中的关键步骤"),
                new GoalRequirementPolicyV7.Draft("r3", "OpenStack 简介中的限制"));

        var normalized = GoalRequirementPolicyV7.normalize(
                "OpenStack-Victoria 部署指南中的 OpenStack 简介部分，前置条件、关键步骤和限制是什么？",
                "DESCRIPTIVE", proposed);

        assertEquals(1, normalized.size());
        assertEquals("core", normalized.getFirst().key());
        assertTrue(normalized.getFirst().description().contains("OpenStack 简介"));
        assertFalse(normalized.getFirst().description().contains("前置条件"));
    }

    @Test
    void operationalGoalWithoutCoreAddsCoreAndKeepsOnlyTwoFacets() {
        var proposed = List.of(
                new GoalRequirementPolicyV7.Draft("r1", "安装前置条件"),
                new GoalRequirementPolicyV7.Draft("r2", "安装步骤"),
                new GoalRequirementPolicyV7.Draft("r3", "安装限制"));

        var normalized = GoalRequirementPolicyV7.normalize(
                "如何安装并配置数据库服务器？", "OPERATIONAL", proposed);

        assertEquals(3, normalized.size());
        assertEquals("core", normalized.getFirst().key());
        assertTrue(normalized.getFirst().description().contains("实际做法"));
        assertEquals("r1", normalized.get(1).key());
        assertEquals("r2", normalized.get(2).key());
    }

    @Test
    void explicitCoreRemainsAuthoritativeAndFirst() {
        var normalized = GoalRequirementPolicyV7.normalize("pkgship 的能力定位是什么？", "DESCRIPTIVE",
                List.of(new GoalRequirementPolicyV7.Draft("r2", "使用限制"),
                        new GoalRequirementPolicyV7.Draft("core", "pkgship 的用途和功能范围")));

        assertEquals(List.of("core", "r2"), normalized.stream()
                .map(GoalRequirementPolicyV7.Draft::key).toList());
    }
}
