# 评测资产

这里保存 VerityForge 当前公开版的可移植蓝图和最终报告。运行数据库、模型凭据和组织内数据不进入仓库。

## 最终报告

| 报告 | 范围 |
| --- | --- |
| [Deep 200 题](deep-rag-final-200-case.md) | `gpt-5.6-luna / low` 的检索、Deep Read、证据覆盖、延迟与 Token |
| [Fast / Deep 完整回答](fast-vs-deep-full-answer.md) | 5 个困难案例的最终回答、语义 Judge、引用支持与耗时 |
| [Auto 质量成本](auto-routing-cost-quality.md) | 默认 50% 成本优先档与 28% 质量优先档 |

## 数据蓝图

| 文件 | 内容 |
| --- | --- |
| `chinese-enterprise-rag-v1.blueprint.json` | 200 篇语料的完整 440 题评测源 |
| `chinese-enterprise-agentic-retrieval-v1.blueprint.json` | 200 题 Deep 困难检索集 |
| `chinese-enterprise-auto-routing-v1.blueprint.json` | 100 Fast + 100 Deep 的路由平衡集 |
| `chinese-enterprise-rag-v1.sources.json` | 200 篇来源选择、上游 Commit、路径、许可与 Source SHA-256 |

同一份完整蓝图也随语料保存在 `data/chinese-enterprise-rag-v1/evaluation/`。仓库提交了 200 个转换后的完整文档，不依赖 Git 忽略目录或另一个版本。

## 验证

验证 200 篇文档、四种格式、Manifest、SHA-256、许可和 440 题蓝图：

```bash
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
```

验证 Auto 路由蓝图可由固定规则重建：

```bash
python3 scripts/build-auto-routing-benchmark.py --check
```

需要重新从固定上游 Commit 构建语料时：

```bash
python3 scripts/build-chinese-enterprise-dataset.py
```

构建器支持 `--cache-dir`、`--offline`、`--clean-cache` 和 `--refresh-selection`；完整选项见 `--help`。重新下载上游内容时仍应核对来源许可，不要把 VerityForge 的源码许可覆盖到第三方语料。

## 导入

本地 API、Worker、对象存储和模型 Profile 就绪后，可以上传完整语料并导入评测：

```bash
scripts/import-chinese-enterprise-dataset.sh
```

导入脚本从本地 `.env` 或显式环境变量读取地址与凭据。不要把访问 Token、密码或运行响应提交到仓库。

## 口径

- 200 题主报告设置 `answerGenerationSkipped=true`，只测研究阶段。
- 5 题报告完整执行回答和引用 Judge，但样本量不足以替代主回归。
- Auto 回放双方都跳过最终回答，节省率不是完整对话账单。
- 失败续跑保留成功 Artifact，并在实际成本中计入失败物理尝试。
- 历史 Pipeline 字符串仅用于识别已保存 Artifact；当前实现统一为 `deep-rag-final`。
