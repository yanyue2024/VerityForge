#!/usr/bin/env python3
"""Build a hard, retrieval-only benchmark for Basic RAG versus Agentic RAG."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SOURCE_BLUEPRINT = ROOT / "benchmarks" / "chinese-enterprise-rag-v1.blueprint.json"
DEFAULT_OUTPUT = ROOT / "benchmarks" / "chinese-enterprise-agentic-retrieval-v1.blueprint.json"
SOURCE_ORDER = ("openeuler", "kubernetes", "ant-design", "apache-doris")
DOMAIN_CONTEXT = {
    "openeuler": "企业操作系统、主机运维与基础设施治理",
    "kubernetes": "云原生集群、工作负载与控制面治理",
    "ant-design": "企业前端组件、交互状态与表单数据流",
    "apache-doris": "分析型数据平台、数据导入与集群运维",
}
PARAPHRASES = (
    ("故障排查", "异常定位与恢复"),
    ("故障诊断", "异常根因定位"),
    ("生命周期", "从创建到终止的状态变化"),
    ("安装", "部署落地"),
    ("配置", "参数设置"),
    ("创建", "搭建"),
    ("管理", "日常治理"),
    ("使用", "实际操作"),
    ("查询", "获取运行信息"),
    ("升级", "版本演进"),
    ("安全", "风险控制"),
    ("概述", "核心定位"),
    ("介绍", "能力定位"),
    ("说明", "约束与做法"),
    ("接口", "调用边界"),
    ("集群", "多节点运行环境"),
    ("容器", "隔离运行单元"),
    ("表单", "业务数据录入流程"),
    ("输入框", "文本录入控件"),
    ("对话框", "阻断式交互窗口"),
    ("表格", "结构化数据视图"),
    ("选择器", "候选项选择控件"),
    ("日志", "运行记录"),
    ("参数", "可调选项"),
)


def normalized(value: str) -> str:
    value = re.sub(r"\{#[^}]+}", "", value)
    value = re.sub(r"[`#*_]+", "", value)
    return re.sub(r"\s+", " ", value).strip(" -：:。")


def paraphrase(value: str) -> str:
    result = normalized(value)
    for source, target in PARAPHRASES:
        result = result.replace(source, target)
    return result or "相关技术任务"


def metadata(challenge: str, source: str, cases: list[dict[str, Any]]) -> dict[str, Any]:
    formats = list(dict.fromkeys(case["metadata"]["sourceFormat"] for case in cases))
    headings = [case["metadata"]["evidenceHeading"] for case in cases]
    quotes = [case["metadata"]["evidenceQuote"] for case in cases]
    return {
        "category": "agentic_retrieval_challenge",
        "challengeType": challenge,
        "difficulty": "hard",
        "recommendedMode": "DEEP",
        "expectNoAnswer": False,
        "sourceFormat": formats[0] if len(formats) == 1 else formats,
        "sourceProject": source,
        "intentCount": len(cases),
        "requiresQueryDecomposition": len(cases) > 1,
        "targetMetric": "recallAt5",
        "evidenceHeading": headings[0] if len(headings) == 1 else headings,
        "evidenceQuote": quotes[0] if len(quotes) == 1 else quotes,
    }


def make_case(
    challenge: str,
    source: str,
    selected: list[dict[str, Any]],
    question: str,
) -> dict[str, Any]:
    aliases = list(dict.fromkeys(case["expectedDocuments"][0] for case in selected))
    answers = [case["expectedAnswer"] for case in selected]
    return {
        "question": question,
        "expectedAnswer": " ".join(
            f"要点{index}：{answer}" for index, answer in enumerate(answers, 1)
        ),
        "expectedDocuments": aliases,
        "metadata": metadata(challenge, source, selected),
    }


def topic(case: dict[str, Any], selectors: dict[str, Any]) -> str:
    alias = case["expectedDocuments"][0]
    title = selectors[alias]["title"]
    heading = case["metadata"]["evidenceHeading"]
    return f"{paraphrase(title)}中的{paraphrase(str(heading))}"


def build() -> dict[str, Any]:
    source = json.loads(SOURCE_BLUEPRINT.read_text(encoding="utf-8"))
    selectors = source["documentSelectors"]
    by_source: dict[str, list[dict[str, Any]]] = {key: [] for key in SOURCE_ORDER}
    for case in source["cases"]:
        if case["metadata"]["category"] == "procedure_condition":
            by_source[case["metadata"]["sourceProject"]].append(case)

    cases: list[dict[str, Any]] = []
    for source_key in SOURCE_ORDER:
        values = by_source[source_key]
        if len(values) != 50:
            raise RuntimeError(f"Expected 50 procedure cases for {source_key}, found {len(values)}")
        domain = DOMAIN_CONTEXT[source_key]

        for index in range(20):
            selected = [values[index], values[(index + 23) % 50]]
            question = (
                f"一次企业变更窗口同时出现两个独立目标：{topic(selected[0], selectors)}；"
                f"{topic(selected[1], selectors)}。请分别定位资料中的前置条件、关键步骤和限制，"
                "不要把两个目标合并成同一项操作。"
            )
            cases.append(make_case("multi_intent", source_key, selected, question))

        for index in range(15):
            selected = [values[index], values[(index + 17) % 50], values[(index + 34) % 50]]
            question = (
                f"围绕{domain}制定一个三阶段核查方案，依次处理："
                f"第一阶段{topic(selected[0], selectors)}；"
                f"第二阶段{topic(selected[1], selectors)}；"
                f"第三阶段{topic(selected[2], selectors)}。"
                "每个阶段分别需要依据哪些规则、参数或操作步骤？"
            )
            cases.append(make_case("query_decomposition", source_key, selected, question))

        for index in range(30, 40):
            selected = [values[index]]
            question = (
                f"在{domain}场景中，团队不记得资料名称，只知道要处理"
                f"“{topic(selected[0], selectors)}”。资料给出的关键条件、边界和做法是什么？"
            )
            cases.append(make_case("semantic_paraphrase", source_key, selected, question))

        for index in range(45, 50):
            selected = [values[index]]
            question = (
                f"现场人员只留下了一条低信息量工单：“需要完成{paraphrase(str(selected[0]['metadata']['evidenceHeading']))}，"
                f"属于{domain}问题。”应从资料中找出哪些具体要求或操作？"
            )
            cases.append(make_case("keyword_sparse", source_key, selected, question))

    if len(cases) != 200:
        raise RuntimeError(f"Expected 200 cases, generated {len(cases)}")
    questions = [case["question"] for case in cases]
    if len(set(questions)) != len(questions):
        raise RuntimeError("Generated questions are not unique")
    for index, case in enumerate(cases, 1):
        case["caseId"] = f"ARH-{index:03d}"

    used_aliases = {alias for case in cases for alias in case["expectedDocuments"]}
    return {
        "schemaVersion": "rag-evaluation-blueprint/v1",
        "benchmarkId": "chinese-enterprise-agentic-retrieval-v1",
        "name": "中文企业技术知识库 Agentic Retrieval 困难集 v1",
        "description": (
            "200条可回答的困难检索题，覆盖多意图、查询拆解、语义改写和低关键词表达，"
            "用于Basic RAG与Agentic RAG的Recall@5对比。"
        ),
        "knowledgeBase": source["knowledgeBase"],
        "expectations": {
            "caseCount": 200,
            "categories": ["agentic_retrieval_challenge"],
        },
        "documentSelectors": {
            alias: selectors[alias] for alias in sorted(used_aliases)
        },
        "cases": cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    result = build()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(result['cases'])} cases to {args.output}")


if __name__ == "__main__":
    main()
