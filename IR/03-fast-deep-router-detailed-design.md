# Fast / Deep 问题路由器详细设计

> 文档状态：实施依据
> 基线提交：`8af4175`
> 上游文档：`01-fast-deep-router-requirements.md`、`02-fast-deep-router-initial-plan.md`

## 1. 设计摘要

新增一个无状态、确定性、零模型调用的 `AutoModeRouter`。它只分析当前用户问题的结构和词面锚点，不访问知识库，不调用检索或模型。`RunCoordinator` 在 `AUTO` 模式下调用该路由器；显式 Fast/Deep 继续直接返回。

路由器使用“Deep 否决优先”的级联：

```text
Deep veto > Fast allow-list > default Deep
```

这不是通用意图分类器，而是一个受质量下限约束的 Fast 准入门。

## 2. 组件与职责

### 2.1 `AutoModeRouter`

建议位置：

```text
modules/rag-application/.../chat/AutoModeRouter.java
```

职责：

- 标准化问题；
- 提取文档引用数量、复杂任务信号和技术标识；
- 输出 `FAST` 或 `DEEP`；
- 输出稳定 reason code 和命中的信号集合；
- 保证任何异常都返回 Deep。

接口语义：

```java
Decision route(String query)

Decision(
    RunMode mode,
    String reasonCode,
    List<String> signals
)
```

`Decision` 必须不可变，`signals` 必须去重并保持稳定顺序。

### 2.2 `RunCoordinator`

改动：

- 注入 `AutoModeRouter`；
- `selectMode` 对显式模式继续返回 `user-override`；
- `AUTO` 调用 Router；
- 把 Router Decision 映射为现有 `Selection`；
- `classifiedByModel=false`，使评测来源保持 `HEURISTIC`。

不得修改 Fast 和 Deep Pipeline 的执行分支。

### 2.3 评测链路

现有 `EvaluationService.executeRouting` 和 `evaluateRoute` 直接调用 `RunCoordinator.selectMode`，因此不新增第二套评测实现。路由评测应得到：

- `routeDecisionSource=HEURISTIC`；
- `classifierAttempted=false`；
- `routerFallback=false`；
- `latencyMs` 为本地决策耗时。

## 3. 标准化

输入处理只做确定性规范化：

1. `null` 转为空字符串；
2. `strip()`；
3. 连续空白折叠为一个空格；
4. ASCII 比较使用小写副本，原字符串不进入 reason；
5. 不做语义改写，不删除中文标点，不截断问题。

空字符串直接返回：

```text
mode=DEEP
reasonCode=auto-deep-empty
```

## 4. 特征提取

### 4.1 文档引用数量

统计成对出现的 `《...》`。数量大于等于 2 视为强 Deep 信号。只出现不完整书名号时不尝试修复，交给默认 Deep。

### 4.2 强 Deep 信号组

| signal | 代表表达 |
|---|---|
| `MULTI_DOCUMENT` | 两个及以上《文档》 |
| `MULTI_GOAL` | 同时、独立目标、两个目标、多个目标、各自 |
| `SEPARATE_ANSWER` | 分别、逐一、不要合并 |
| `STAGED_TASK` | 第一阶段、第二阶段、三阶段、依次处理 |
| `SYNTHESIS` | 综合、跨文档、多份资料 |
| `COMPARISON` | 比较、对比、差异、权衡、优缺点 |
| `CONFLICT` | 冲突、矛盾、相互印证 |
| `DIAGNOSE_AND_REPAIR` | 根因/原因与修复/方案/措施同时出现 |

任一强 Deep 信号命中即选择 Deep，不继续评估 Fast 准入。

### 4.3 高置信 Fast 信号组

#### A. 单文档直接定位

必须同时满足：

- 恰好一个完整 `《...》`；
- 没有强 Deep 信号；
- 命中“根据、在某部分、文档说明、关键信息、要求或做法”等直接定位表达。

reason：`auto-fast-explicit-document`。

#### B. 简单资料存在性判断

必须同时满足：

- 没有强 Deep 信号；
- 命中“是否给出、是否包含、是否规定、是否说明、有没有提供”等表达；
- 不同时要求比较、原因、方案或多目标输出。

reason：`auto-fast-simple-lookup`。

#### C. 稳定技术标识

必须同时满足：

- 没有强 Deep 信号；
- 至少存在一个以英文字母开头、总长度不少于 3 的技术 Token；
- Token 可包含数字、点、下划线和连字符；
- 排除普通英文疑问/连接词，例如 `what`、`how`、`why`、`the`、`and`、`for`。

示例：

```text
Kubernetes
ResourceClaimSpec
CSS-in-JS
Webhook
HDFS
```

反例：

```text
IP       // 太短
v1       // 太短
how      // 普通疑问词
```

reason：`auto-fast-technical-anchor`。

### 4.4 默认

其余全部返回：

```text
mode=DEEP
reasonCode=auto-deep-uncertain
```

## 5. 决策伪代码

```text
route(query):
  normalized = normalize(query)
  if normalized is empty:
      return DEEP / auto-deep-empty

  features = extract(normalized)

  if features.hasStrongDeepSignal:
      return DEEP / features.primaryDeepReason

  if features.isExplicitSingleDocumentLookup:
      return FAST / auto-fast-explicit-document

  if features.isSimpleAvailabilityLookup:
      return FAST / auto-fast-simple-lookup

  if features.hasStableTechnicalAnchor:
      return FAST / auto-fast-technical-anchor

  return DEEP / auto-deep-uncertain
```

## 6. 优先级与不变量

必须保持以下不变量：

1. `DEEP veto` 永远高于技术标识；包含 `Kubernetes` 的多目标问题仍然是 Deep；
2. 两个明确文档永远不会因为标题中包含英文而进入 Fast；
3. 默认分支永远是 Deep；
4. 规则不能读取 `recommendedMode`、`challengeType`、答案和期望文档；
5. 规则不能包含 `ARH-*`、`AFR-*` 等 Case ID 特判；
6. Router 不持有组织、用户或知识库状态；
7. 同一问题在任何线程和任何组织下决策一致。

## 7. 接入与事件

`RunCoordinator.selectMode`：

```text
requested != AUTO
  -> Selection(requested, "user-override", false)

requested == AUTO
  -> decision = autoModeRouter.route(query)
  -> Selection(decision.mode, decision.reasonCode, false)
```

`ROUTE_SELECTED` 复用当前事件结构。reason code 必须短小稳定；详细 `signals` 只在 Router 单元测试和后续诊断接口需要时使用，本版不把原始特征全文写入事件。

## 8. 失败处理

Router 是纯本地逻辑，正常情况下不应抛出异常。仍需在公共入口实现 Fail Deep：

```text
RuntimeException
  -> DEEP / auto-deep-router-error
```

不得回退旧 LLM Router，也不得让路由失败导致 Run 失败。

## 9. 测试设计

### 9.1 单元测试

必须覆盖：

- 显式单文档直查 -> Fast；
- 简单是否存在 -> Fast；
- 单目标稳定技术标识 -> Fast；
- 两文档综合，即使包含英文 -> Deep；
- 两目标分别回答 -> Deep；
- 三阶段问题 -> Deep；
- 比较、冲突、根因与方案 -> Deep；
- 中文模糊单问 -> Deep；
- 空问题 -> Deep；
- 决策结果不可变且 reason 稳定。

### 9.2 Coordinator 测试

- `AUTO` 不再固定 Deep；
- AUTO Fast 和 AUTO Deep 都能被选择；
- 显式 Fast/Deep 不调用 Router 或不改变结果；
- Router 决策 `classifiedByModel=false`。

### 9.3 数据集验收

使用两类互补数据：

1. AUTO 平衡 200 题：检查 100 条明确 Fast 是否稳定进入 Fast、复杂问题是否被 Deep veto 保护；
2. Agentic 困难 200 题：按生产代码决策，在历史 Fast 与 v8 Deep 逐题结果中选择对应结果，计算混合指标。

平衡集的 `recommendedMode` 是保守人工标签。稳定技术标识题即使标签为 Deep，只要配对历史结果证明 Fast 不降低质量，也允许进入 Fast；因此最终门禁以混合 Recall 和成本为主，标签 Accuracy 为辅助诊断。

## 10. 离线回放口径

Fast 历史 Run：

```text
9da94cf0-7e48-4f44-b216-ccd032411bfe
```

v8 Deep 历史 Run：

```text
196f3c20-c164-4dfb-8e1f-8f852e5f8ba8
c499ed92-c3f7-4bdf-aec1-7a5b9184523f
ffae8878-105a-44cf-9159-ac313794ed0d
1d3cc762-df84-4821-b34f-93d3634341b9
```

按 `metadata.benchmarkCaseId` 与问题文本双重核对后配对。每题根据 Router 选择读取 Fast 或 Deep 的：

- Recall@5；
- Recall@10；
- latencyMs；
- Deep totalTokens；Fast Retrieval-only 的研究模型 Token 记为 0。

聚合公式：

```text
RoutedRecall@K = avg(selectedResult.recallAtK)
RoutedTokens = sum(selectedMode == DEEP ? deep.totalTokens : 0)
TokenSaving = 1 - RoutedTokens / AllDeepTokens
RoutedLatency = avg(selectedMode == DEEP ? deep.latencyMs : fast.latencyMs)
TimeSaving = 1 - RoutedLatency / AllDeepLatency
RecallDelta = RoutedRecall@K - AllDeepRecall@K
```

## 11. 发布门禁

| 门禁 | 阈值 |
|---|---:|
| Router 模型调用 | 0 |
| Router Token | 0 |
| Router 外部请求 | 0 |
| 困难集 Routed Recall@5 | `>= 0.90` |
| 相对全 Deep Recall@5 下降 | 目标 `<= 0.02`，硬上限 `0.063333` |
| 研究 Token 节省 | `>= 5%` |
| 研究时间节省 | `>= 5%` |
| AUTO 平衡集明确 Fast 召回率 | `>= 0.98` |
| Fail Deep 用例 | 100% |

如果初版已经达到 Recall 无损且成本节省超过 5%，不继续扩大 Fast 准入面；避免为追求更高 Fast 比例破坏已获得的质量收益。
