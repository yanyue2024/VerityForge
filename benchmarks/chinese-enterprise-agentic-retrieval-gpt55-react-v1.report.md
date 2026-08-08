# GPT-5.5 WeKnora-v2 ReAct 检索评测报告

## 运行快照

- Evaluation Run：`cf75a931-2e50-409d-87c1-5855579c6922`
- 数据集：`7415acd4-3858-4c0b-93ae-b4c09179120b`
- 知识库：`a350ed89-123f-4af4-a8f3-2720dd0c67a3`
- Chat Profile：`50873e75-7244-479b-aecf-5faf58802d97`（`gpt-5.5`）
- Pipeline：`agentic-react-v1`
- Prompt：`weknora-progressive-rag-v1`
- 执行模式：`AGENTIC_RETRIEVAL_ONLY`，并发数 `2`
- 结果：`COMPLETED`，200 个唯一成功题，0 个失败题；200 题均跳过最终答案生成

本报告只评价召回和 Agent 工具行为，不评价最终自然语言答案。原始逐题 JSON 保存在评测 API（按上面的 Run ID 查询），完整 Markdown 明细由 `scripts/render-agentic-retrieval-report.py` 生成。

## 召回指标

| 指标 | GPT-5.5 ReAct | Basic 参考 | 旧固定 Agentic 参考 | WeKnora-v2 Qwen 参考 |
| --- | ---: | ---: | ---: | ---: |
| Recall@5 | 0.8550 | 0.6067 | 0.7592 | 0.5767 |
| Recall@10 | 0.8975 | 0.6567 | 0.7925 | 0.6150 |
| Hit@5 / Hit@10 | 0.9400 / 0.9550 | — | — | — |
| Precision@5 / Precision@10 | 0.4450 / 0.3850 | — | — | — |
| MRR@5 | 0.8758 | — | — | — |
| nDCG@5 / nDCG@10 | 0.8313 / 0.8481 | — | — | — |
| MAP@5 / MAP@10 | 0.7930 / 0.8042 | — | — | — |
| Evidence answer coverage | 0.8663 | — | — | — |

与 Basic 逐题配对比较：Recall@5 improved `93`、tied `99`、regressed `8`。

## ReAct 能力与运行诊断

- Tool calls：`974`；平均每题 `4.87`；工具失败 `7`，失败率 `0.7187%`。工具错误作为 Tool Result 回填模型，没有造成评测题失败。
- 平均 iterations：`5.865`；预算拒绝 `0`；context compression `0`。
- 全工具 coverage recall：`0.9208`。
- Deep-read recall：`0.7150`；deep-read 合规率：`0.9300`。
- 严格发现顺序 Recall@5 / @10：`0.7642 / 0.8967`。
- Token usage：input `18,544,993`、output `520,494`、total `19,065,487`。
- 延迟 P50 / P95 / P99：`70,098 / 112,977 / 122,779 ms`。

按 challengeType 的 Recall@5：`keyword_sparse 0.6500`、`multi_intent 0.9000`、`query_decomposition 0.8167`、`semantic_paraphrase 0.9250`。按 sourceProject：`ant-design 0.8867`、`apache-doris 0.8967`、`kubernetes 0.8700`、`openeuler 0.7667`。按 intentCount：1 项 `0.8333`、2 项 `0.9000`、3 项 `0.8167`。

## 硬门槛核验

- Native Tool Calling 探测：PASS；Profile capabilities 保存 `toolCalling=true`。
- 200 个唯一成功题：PASS（`successfulCases=200`，`failedCases=0`）。
- Retrieval-only：PASS；结果记录 `answerGenerationSkipped=true`，逐题无最终答案字段、无 citation；未写入 conversation assistant message。
- 有效版本泄漏：PASS，`effectiveVersionLeakCount=0`。
- Restricted/跨组织泄漏：PASS，`forbiddenDocumentHitCount=0`；无可评分 forbidden 样本时不把 `forbiddenDocumentLeakFreeRate=0` 误读为泄漏率。
- 质量指标首轮仅诊断，不作为发布阻断条件。

## 复现

```bash
python3 scripts/render-agentic-retrieval-report.py \
  /path/to/full200.json \
  --basic-run /path/to/basic-run.json \
  --output /path/to/full200-report.md
```

