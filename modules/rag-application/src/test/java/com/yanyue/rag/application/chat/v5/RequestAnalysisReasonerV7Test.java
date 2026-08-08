package com.yanyue.rag.application.chat.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RequestAnalysisReasonerV7Test {
    private final RequestAnalysisReasonerV5 reasoner = new RequestAnalysisReasonerV5(null, new ObjectMapper());

    @Test
    void v7ParseAppliesCoreRequirementAndKeywordPolicyWithoutChangingV5Parse() {
        var raw = """
                {
                  "standaloneObjective":"说明 CRI 调用边界",
                  "objectiveRequirements":[
                    {"key":"o1","description":"回答 CRI 调用边界","mandatory":true,"mappedGoalKeys":["g1"]}
                  ],
                  "answerConstraints":[],
                  "goals":[{
                    "key":"g1",
                    "goalType":"DESCRIPTIVE",
                    "question":"CRI调用边界中的描述，其前置条件、关键步骤和限制是什么？",
                    "requirements":[
                      {"key":"r1","description":"前置条件"},
                      {"key":"r2","description":"关键步骤"},
                      {"key":"r3","description":"限制"}
                    ],
                    "primaryQueries":[
                      {"role":"PRIMARY_KEYWORD","searchMode":"KEYWORD","text":"CRI调用边界"},
                      {"role":"PRIMARY_SEMANTIC","searchMode":"SEMANTIC","text":"CRI 的接口定义和职责是什么"}
                    ]
                  }]
                }
                """;

        var v7 = reasoner.parseV7(raw);
        var v5 = reasoner.parse(raw);

        assertEquals(1, v7.goals().getFirst().requirements().size());
        assertTrue(v7.goals().getFirst().requirements().getFirst().description().contains("CRI调用边界"));
        assertEquals("CRI接口 OR CRI", v7.goals().getFirst().primaryQueryPair().keywordQuery().text());
        assertEquals(v7.goals().getFirst().requirementIds(),
                v7.goals().getFirst().primaryQueryPair().keywordQuery().targetRequirementIds());

        assertEquals(3, v5.goals().getFirst().requirements().size());
        assertEquals("CRI调用边界", v5.goals().getFirst().primaryQueryPair().keywordQuery().text());
    }

    @Test
    void v7KeepsModelSuppliedCoreAndAtMostTwoAdditionalFacets() {
        var raw = """
                {
                  "standaloneObjective":"安装数据库服务器",
                  "objectiveRequirements":[
                    {"key":"o1","description":"安装数据库服务器","mandatory":true,"mappedGoalKeys":["g1"]}
                  ],
                  "answerConstraints":[],
                  "goals":[{
                    "key":"g1",
                    "goalType":"OPERATIONAL",
                    "question":"如何安装数据库服务器？",
                    "requirements":[
                      {"key":"r1","description":"准备条件"},
                      {"key":"core","description":"数据库服务器的实际安装过程"},
                      {"key":"r2","description":"限制"}
                    ],
                    "primaryQueries":[
                      {"role":"PRIMARY_KEYWORD","searchMode":"KEYWORD","text":"安装数据库服务器"},
                      {"role":"PRIMARY_SEMANTIC","searchMode":"SEMANTIC","text":"数据库服务器应如何安装"}
                    ]
                  }]
                }
                """;

        var analysis = reasoner.parseV7(raw);

        assertEquals("数据库服务器的实际安装过程",
                analysis.goals().getFirst().requirements().getFirst().description());
        assertEquals(3, analysis.goals().getFirst().requirements().size());
    }

    @Test
    void v8应按原问题目标校正双路Query并移除场景污染() {
        var raw = """
                {
                  "standaloneObjective":"分别查找使用方法和 Gnome 桌面资料",
                  "objectiveRequirements":[
                    {"key":"o1","description":"回答两个目标","mandatory":true,"mappedGoalKeys":["g1","g2"]}
                  ],
                  "answerConstraints":[],
                  "goals":[
                    {
                      "key":"g1","goalType":"OPERATIONAL",
                      "question":"在企业变更窗口中，实际操作方法包含哪些约束与做法？",
                      "requirements":[
                        {"key":"core","description":"企业变更窗口中的审批、记录和实施流程"},
                        {"key":"docs","description":"实际操作方法的文档约束"}
                      ],
                      "primaryQueries":[
                        {"role":"PRIMARY_KEYWORD","searchMode":"KEYWORD","text":"变更窗口 操作方法 OR 在企业变更窗口 OR 实际操作方法"},
                        {"role":"PRIMARY_SEMANTIC","searchMode":"SEMANTIC","text":"检索企业变更窗口中的实际操作方法"}
                      ]
                    },
                    {
                      "key":"g2","goalType":"DESCRIPTIVE",
                      "question":"Gnome 用户指南 2.1 桌面的组成和入口是什么？",
                      "requirements":[{"key":"core","description":"Gnome 桌面组成"}],
                      "primaryQueries":[
                        {"role":"PRIMARY_KEYWORD","searchMode":"KEYWORD","text":"Gnome 用户指南 2.1 桌面"},
                        {"role":"PRIMARY_SEMANTIC","searchMode":"SEMANTIC","text":"Gnome 桌面的组成和入口"}
                      ]
                    }
                  ]
                }
                """;
        var original = "一次企业变更窗口同时出现两个独立目标：实际操作方法中的文档约束与做法；"
                + "Gnome 用户指南中的2.1 桌面。请分别定位资料，不要合并。";

        var analysis = reasoner.parseV8(raw, original);

        assertEquals("使用方法 OR 操作方法 OR 实际操作方法",
                analysis.goals().getFirst().primaryQueryPair().keywordQuery().text());
        assertEquals("使用方法和操作方法中的文档约束与做法", analysis.goals().getFirst().question());
        assertEquals(1, analysis.goals().getFirst().requirements().size());
        assertEquals("核验“使用方法和操作方法中的文档约束与做法”对应资料明确给出的前提条件、边界限制、关键做法和必要步骤",
                analysis.goals().getFirst().requirements().getFirst().description());
        assertEquals("查找并说明使用方法和操作方法资料明确给出的前提条件、边界限制、关键做法和必要步骤",
                analysis.goals().getFirst().primaryQueryPair().semanticQuery().text());
        assertEquals("Gnome 用户指南 2.1 桌面 OR Gnome 用户指南",
                analysis.goals().get(1).primaryQueryPair().keywordQuery().text());
        assertTrue(analysis.goals().get(1).primaryQueryPair().semanticQuery().text()
                .startsWith("查找并说明Gnome 用户指南文档中的2.1 桌面"));

        var single = reasoner.parseV8(raw,
                "团队不记得资料名称，只知道要处理“Gnome 用户指南中的2.1 桌面”。请查找资料。");
        assertEquals(1, single.goals().size());
        assertEquals("Gnome 用户指南中的2.1 桌面", single.standaloneObjective());
        assertEquals("Gnome 用户指南中的2.1 桌面", single.goals().getFirst().question());
        assertEquals(java.util.Set.of(single.goals().getFirst().id()),
                single.objectiveRequirements().getFirst().mappedGoalIds());
    }

    @Test
    void v8核心定位应保留定义架构角色和用途证据面() {
        var raw = """
                {
                  "standaloneObjective":"说明容器镜像构建的功能",
                  "objectiveRequirements":[
                    {"key":"o1","description":"说明功能","mandatory":true,"mappedGoalKeys":["g1"]}
                  ],
                  "answerConstraints":[],
                  "goals":[{
                    "key":"g1","goalType":"DESCRIPTIVE","question":"容器镜像构建的功能与作用",
                    "requirements":[{"key":"core","description":"核验核心内容"}],
                    "primaryQueries":[
                      {"role":"PRIMARY_KEYWORD","searchMode":"KEYWORD","text":"镜像构建"},
                      {"role":"PRIMARY_SEMANTIC","searchMode":"SEMANTIC","text":"说明镜像构建功能"}
                    ]
                  }]
                }
                """;

        var analysis = reasoner.parseV8(raw,
                "团队不记得资料名称，只知道要处理“隔离运行单元镜像构建中的核心定位”。请查找资料。");

        assertEquals("核验“容器镜像构建中的核心定位”对应对象的定义或身份、架构或组成、角色职责关系及主要用途",
                analysis.goals().getFirst().requirements().getFirst().description());
        assertEquals("查找并说明容器镜像构建的定义或身份、架构或组成、角色职责关系及主要用途",
                analysis.goals().getFirst().primaryQueryPair().semanticQuery().text());
    }

    @Test
    void v8应在三阶段目标列表结束处停止并移除阶段序号() {
        var question = "围绕企业操作系统制定三阶段核查方案，依次处理："
                + "第一阶段实际操作方法中的文档约束与做法；"
                + "第二阶段实际操作GCC编译中的实际操作GCC编译；"
                + "第三阶段异常根因定位模块中的能力定位。"
                + "每个阶段分别需要依据哪些规则、参数或操作步骤？";

        assertEquals(java.util.List.of(
                        "实际操作方法中的文档约束与做法",
                        "实际操作GCC编译中的实际操作GCC编译",
                        "异常根因定位模块中的能力定位"),
                reasoner.originalTargetsV8(question));
    }

    @Test
    void v8只应提取具有明确语法边界的单目标() {
        assertEquals(java.util.List.of("日常治理服务中的简介"), reasoner.originalTargetsV8(
                "在企业操作系统场景中，团队不记得资料名称，只知道要处理“日常治理服务中的简介”。"
                        + "资料给出的关键条件、边界和做法是什么？"));
        assertEquals(java.util.List.of("3.1.4.4 通知中心"), reasoner.originalTargetsV8(
                "现场人员只留下了一条低信息量工单：“需要完成3.1.4.4 通知中心，属于企业操作系统问题。”"
                        + "应从资料中找出哪些具体要求或操作？"));
        assertEquals(java.util.List.of(), reasoner.originalTargetsV8("普通的单目标问题应该如何回答？"));
    }
}
