# 评测设计

VerityForge 的评测目标不是只给出一个“准确率”，而是分别检查候选召回、Goal 覆盖、证据接纳、最终回答、引用支持、成本和运行可靠性。评测调用与聊天相同的生产 Pipeline，并把每个结果关联到真实 `rag_run`。

## 数据层次

### 440 题完整蓝图

[中文企业技术知识库 v1](../data/chinese-enterprise-rag-v1/README.md) 随仓库提交 440 题完整蓝图，覆盖直接事实、流程、语义表达、多意图、拆解和无答案等场景。它是公开语料的完整回归源。

### 200 题困难检索集

主报告使用从同一知识库构建的 200 题困难集：

| 类型 | 数量 | 测试目标 |
| --- | ---: | --- |
| `multi_intent` | 80 | 两个独立目标能否分别召回并获得证据 |
| `query_decomposition` | 60 | 三阶段或复合问题能否拆成平衡 Goal |
| `semantic_paraphrase` | 40 | 不复述标题和关键词时能否语义召回 |
| `keyword_sparse` | 20 | 极低信息表达能否恢复实体或标题锚点 |

四个来源各 50 题，避免某个文档项目主导总体分数。运行模型为 `gpt-5.6-luna`，`reasoning_effort=low`。

### 5 题完整回答集

200 题为了可控成本和定位检索问题，使用 `answerGenerationSkipped=true`。另选 5 个困难多目标案例，启用最终回答和模型 Judge，对 Fast / Deep 做成对比较。这个小集合负责验证答案装包、语义与引用，不用于声称整体泛化准确率。

### Auto 200 题路由集

Auto 集包含 100 个标注 Fast 和 100 个标注 Deep 的路由案例，并复用真实 Fast / Deep 研究结果计算质量成本曲线。路由标签用于分析决策，不替代最终 Recall、RCC 与 Token 指标。

## 指标定义

| 指标 | 解释 |
| --- | --- |
| Recall@K | 期望文档中有多少在前 K 个结果内被召回 |
| Hit@K | 一个案例的前 K 是否至少命中一个期望文档 |
| MRR@5 | 首个正确文档排名的倒数，最多观察前 5 |
| nDCG@K | 同时考虑命中相关性与排序位置 |
| AEC | Accepted Evidence Coverage，期望证据被 Deep 接纳的覆盖程度 |
| RCC | Requirement Coverage Consistency，Requirement 与证据覆盖的一致性 |
| Answer Coverage | 期望答案要点在最终回答中的覆盖 |
| Semantic Correctness | 模型 Judge 对最终答案语义正确性的评分 |
| Citation Support | 引用证据对相应回答陈述的支持程度 |
| Citation Resolvability | 引用是否能解析到当前保存的文档版本和 Chunk |
| Leakage | 是否命中越权范围、错误组织或非生效版本 |

没有对应标注的案例不会用零值污染某项聚合分母。模型 Judge 失败会单独记录，RAG 结果本身仍保留。

## 运行和续跑

每个独立问题使用隐藏会话；带 `conversationGroup` / `conversationTurn` 的问题在同一隐藏会话中顺序执行，以验证真实多轮记忆。某一轮失败时后续轮次跳过，避免拿不完整上下文继续打分。

运行快照固定数据集、知识范围、过滤器、模型 Profile、Pipeline 配置和 Judge 模式。失败案例可以增量续跑，成功案例保持原 Artifact。主 200 题首轮成功 192 题，随后两次只恢复 6 和 2 题；最终汇总 200 个唯一成功案例，同时成本统计保留全部 210 次物理尝试。

## 公开结果

### Deep 200 题，检索与证据阶段

| 指标 | 结果 |
| --- | ---: |
| Recall@5 / Recall@10 | 0.9758 / 0.9808 |
| MRR@5 | 0.9442 |
| nDCG@5 / nDCG@10 | 0.9508 / 0.9531 |
| AEC / RCC | 0.9191 / 0.9373 |
| P50 / P95 / P99 | 38.6s / 60.5s / 67.5s |
| 最终成功结果 Token | 7,489,964 |
| 含失败尝试的实际 Token | 8,056,623 |
| 工具失败 / 范围泄漏 / 版本泄漏 | 0 / 0 / 0 |

### Fast / Deep 5 题，完整回答

| 指标 | Deep | Fast |
| --- | ---: | ---: |
| Recall@5 | 1.0000 | 0.3667 |
| 回答覆盖 | 0.5460 | 0.2121 |
| 语义正确性 | 0.9740 | 0.1500 |
| 引用支持 | 0.9460 | 0.3500 |
| 平均耗时 | 78.6s | 21.3s |

![评测工作台](showcase/evaluation-fast-deep.png)

## 可复现资产

- `data/chinese-enterprise-rag-v1/evaluation/`：440 题完整蓝图。
- `benchmarks/chinese-enterprise-agentic-retrieval-v1.blueprint.json`：200 题困难集。
- `benchmarks/chinese-enterprise-auto-routing-v1.blueprint.json`：200 题路由集。
- `benchmarks/deep-rag-final-200-case.md`：主运行 ID、分类型、分来源和失败恢复。
- `benchmarks/fast-vs-deep-full-answer.md`：5 题逐题完整回答结果。
- `benchmarks/auto-routing-cost-quality.md`：两个 Auto 档位。

语料完整性验证：

```bash
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
python3 scripts/build-auto-routing-benchmark.py --check
```

公开仓库不包含模型密钥、数据库快照或运行中的组织数据。报告保存足够的运行口径与聚合结果，但无法在没有同类模型服务的情况下保证逐 Token 重放。
