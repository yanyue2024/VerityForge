# Fast / Deep 问题路由器验收报告

> 验收日期：2026-07-28
> 基线：Agentic RAG v8，提交 `8af4175`
> 验收方式：Router 新测试 + 历史 Fast/Deep Artifact 离线回放
> 新 Fast/Deep 模型运行：0

## 1. 实现结果

本次实现完成了：

- 新增零模型调用的 `AutoModeRouter`；
- `AUTO` 从固定 Deep 改为本地 Fast/Deep 路由；
- Deep 否决优先，高置信 Fast 准入，其余默认 Deep；
- 显式 Fast/Deep 用户覆盖保持不变；
- 复用现有 `ROUTE_SELECTED`、`routeDecisionSource=HEURISTIC` 和 `ROUTING_ONLY` 评测协议；
- 未修改 Fast Pipeline 和 v8 Deep Pipeline。

## 2. 数据与口径

### 2.1 Fast 历史结果

```text
Evaluation Run: 9da94cf0-7e48-4f44-b216-ccd032411bfe
Execution: RETRIEVAL_ONLY
Cases: 200/200
```

该 Run 使用原问题执行 Keyword Top30 + Semantic Top30 + RRF Top40，是 Fast 主检索的历史低成本代理。它在 Rerank、上下文打包和最终回答前结束。

### 2.2 v8 Deep 历史结果

```text
196f3c20-c164-4dfb-8e1f-8f852e5f8ba8
c499ed92-c3f7-4bdf-aec1-7a5b9184523f
ffae8878-105a-44cf-9159-ac313794ed0d
1d3cc762-df84-4821-b34f-93d3634341b9
```

四批均为 50/50 健康的 `AGENTIC_RETRIEVAL_ONLY`，合计 200 题。按 `benchmarkCaseId` 与问题文本和 Fast Run 配对。

### 2.3 成本解释

双方都没有执行最终答案生成，因此本报告的 Token 和时间是“检索/研究阶段成本”：

- Fast 历史结果的生成式研究 Token 为 0；
- Deep 包含 Planner、Deep Read、Judge 等研究 Token；
- Router 本身不调用模型，新增 Token 为 0；
- 生产完整回答中 Fast 和 Deep 都还会支付最终答案生成成本，因此端到端百分比节省会小于本报告的研究阶段百分比；
- 被避免的 Deep 研究绝对成本是真实历史用量，不是模型估算。

## 3. Router 决策验收

### 3.1 AUTO 平衡 200 题

数据集：`中文企业技术知识库 AUTO 路由平衡集 v1`。

| 指标 | 结果 |
|---|---:|
| 样本数 | 200 |
| 人工推荐 Fast | 100 |
| 推荐 Fast 被选为 Fast | 100/100 |
| 人工推荐 Deep | 100 |
| 推荐 Deep 被选为 Deep | 88/100 |
| 最终选择 Fast | 112/200，56% |
| 最终选择 Deep | 88/200，44% |
| 与保守推荐标签一致率 | 188/200，94% |

12 条保守标签为 Deep、但 Router 选择 Fast 的问题都属于单目标稳定技术标识。它们能够映射到困难集的既有双模式结果：

| 指标 | Deep | Fast |
|---|---:|---:|
| 配对题数 | 12 | 12 |
| Recall@5 | 0.916667 | 0.916667 |
| 平均研究时间 | 32.410 秒 | 0.771 秒 |
| 避免的 Deep Token | 130,596 | - |

因此这些偏离是基于实际效果的成本选择，不是分类失败。人工 `recommendedMode` 作为辅助诊断保留，最终门禁以混合质量和成本为准。

### 3.2 困难 200 题路由分布

| 模式 | 题数 | 占比 |
|---|---:|---:|
| Fast | 28 | 14% |
| Deep | 172 | 86% |

28 条 Fast 均来自没有复杂信号的稳定技术锚点：

| 题型 | Fast 题数 | Fast R@5 | Deep R@5 | 避免 Token |
|---|---:|---:|---:|---:|
| Semantic Paraphrase | 21 | 1.0000 | 1.0000 | 232,938 |
| Keyword Sparse | 7 | 0.7143 | 0.7143 | 73,489 |

多意图、三阶段拆解和跨文档问题全部由 Deep veto 保护。

## 4. 核心验收指标

### 4.1 质量

| 指标 | 全 Deep v8 | AUTO Router | 变化 |
|---|---:|---:|---:|
| Recall@5 | 0.963333 | **0.963333** | **0.000000** |
| Recall@10 | 0.963333 | **0.963333** | **0.000000** |
| MRR@5 | **0.947500** | 0.940000 | -0.007500 |
| Research / Expected Answer Coverage | 0.821195 | **0.835389** | +0.014194 |

逐题 Recall@5 变化：

| 结果 | 题数 |
|---|---:|
| 改善 | 1 |
| 持平 | 198 |
| 回退 | 1 |

Router 在宏平均 Recall 上无损，但不是逐题零回退。MRR 的下降说明部分 Fast 命中的正确文档排名低于 Deep；这是当前成本收益点的次级代价。

### 4.2 Token

| 指标 | 全 Deep v8 | AUTO Router | 变化 |
|---|---:|---:|---:|
| 200 题研究 Token | 3,269,084 | 2,962,657 | -306,427 |
| 平均每题研究 Token | 16,345.42 | 14,813.29 | -1,532.14 |
| Token 节省率 | - | **9.3735%** | 达标 |
| Router 自身 Token | - | **0** | 达标 |

### 4.3 时间

| 指标 | 全 Deep v8 | AUTO Router | 变化 |
|---|---:|---:|---:|
| 平均研究时间 | 46.850 秒 | 42.512 秒 | **-9.2600%** |
| P50 | 45.156 秒 | 45.090 秒 | -0.066 秒 |
| P95 | 74.449 秒 | 74.449 秒 | 基本不变 |
| Router 本地 P95 | - | `< 2 ms` 测试门禁通过 | 达标 |

只有 14% 困难问题被分流，所以平均时间下降明显，P50/P95 不会同步大幅下降。若真实流量更接近 AUTO 平衡集的 56% Fast 比例，平均成本收益会更大，但不能用该分布替代生产流量观测。

## 5. 与需求门禁对照

| 门禁 | 要求 | 结果 | 状态 |
|---|---:|---:|---|
| Router 模型调用 | 0 | 0 | 通过 |
| Router Token | 0 | 0 | 通过 |
| Router 外部请求 | 0 | 0 | 通过 |
| 困难集 Recall@5 | >= 0.90 | 0.963333 | 通过 |
| Recall@5 相对全 Deep 下降 | <= 0.063333 | 0 | 通过 |
| 研究 Token 节省 | >= 5% | 9.3735% | 通过 |
| 平均研究时间节省 | >= 5% | 9.2600% | 通过 |
| 明确 Fast 识别率 | >= 98% | 100% | 通过 |
| 不确定问题 Fail Deep | 100% 定向测试 | 100% | 通过 |

## 6. 测试结果

定向测试：

```text
AutoModeRouterTest                 6/6
AutoModeRouterBenchmarkTest        3/3
RunCoordinatorTest                 5/5
合计                              14/14
```

覆盖内容包括规则优先级、Deep veto、显式 Fast/Deep 用户覆盖、技术锚点、Fail Deep、AUTO 的 Fast/Deep 双分支接入、路由来源与信号事件、两个 200 题蓝图分布和本地 P95 延迟。

全仓库门禁：

```text
./mvnw test                 267/267，0 失败，0 跳过
./mvnw -DskipTests package  7/7 模块打包成功
git diff --check            通过
```

## 7. 迭代结论

初版已经达到一个更优的保守收益点：

```text
Recall@5/10 不下降
+ Research Coverage 略升
+ Token 节省 9.37%
+ 平均研究时间节省 9.26%
- MRR@5 下降 0.0075
- P95 不变
```

因此本轮不继续放宽 Fast 准入。将所有 Semantic Paraphrase 改走 Fast 虽可节省约 15% Token，但历史回放 Recall@5 会降至 `0.898333`；将所有单意图改走 Fast 会降至 `0.883333`。两者都不是更好的默认产品配置。

后续线上阶段应重点监控：

- `auto-fast-technical-anchor` 的真实问题占比；
- Fast 后用户追问或改走 Deep 的比例；
- 分 reason code 的 Recall、无答案率和答案投诉；
- 全链路端到端 Token/延迟，而非只看本报告的研究阶段节省；
- 新领域中 ASCII 技术标识是否仍保持高 Fast 精度。

在获得真实流量数据前，不建议增加远程 LLM Router 或继续扩大 Fast 规则。
