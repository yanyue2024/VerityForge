# 中文企业技术知识库 v1

这是 VerityForge 公开版使用的完整知识库语料：200 篇内容互不重复的公开许可中文企业技术文档，
PDF、DOCX、HTML、Markdown 各 50 篇，以及 440 条带标准答案、目标文档别名和原文证据的 RAG 评测题。

## 组成

| 来源 | PDF | DOCX | HTML | Markdown | 合计 | 许可 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| openEuler | 13 | 13 | 12 | 12 | 50 | CC BY-SA 4.0 |
| Kubernetes | 12 | 13 | 13 | 12 | 50 | CC BY 4.0 |
| Ant Design | 12 | 12 | 13 | 13 | 50 | MIT |
| Apache Doris | 13 | 12 | 12 | 13 | 50 | Apache-2.0 |

语料总大小：15.0 MiB。具体来源、固定提交、原始 URL、许可证和校验和见
`metadata/manifest.jsonl`。转换后的文档继续遵守各自上游许可证，尤其 openEuler 内容仍为
CC BY-SA 4.0。本目录不对全部语料重新授予统一许可证。

## 使用

从项目根目录验证数据集：

```bash
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
```

重新构建：

```bash
python3 scripts/build-chinese-enterprise-dataset.py
```

批量上传并在全部文档成功后导入评测集：

```bash
scripts/import-chinese-enterprise-dataset.sh
```

`corpus/` 中每篇逻辑文档只保留一种格式。评测 blueprint 使用稳定文档别名；导入脚本会在目标知识库中
按唯一标题解析环境相关的文档 UUID。
