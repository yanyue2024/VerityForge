# VerityForge 设计与证据索引

根目录 [README](../README.md) 负责作品集式概览；这里展开当前公开版的系统边界、关键决策、实现约束与评测证据。文档只描述一套当前实现，不要求读者先理解任何历史版本。

## 产品与链路

| 主题 | 说明 |
| --- | --- |
| [知识库与入库](knowledge-base.md) | 多格式解析、父子分块、Metadata、版本发布、索引代际与 200 篇公开语料 |
| [Fast 模式](fast-mode.md) | 会话改写、混合检索、RRF、Rerank、上下文装包、一次流式回答 |
| [Deep 模式](deep-mode.md) | Goal 拆解、并发研究、父块深读、证据判断、缺口补检与最终回答 |
| [Auto 模式](auto-mode.md) | 无额外路由模型的检索感知决策、失败保护以及 50% 成本优先默认档 |
| [评测设计](evaluation.md) | 200 题困难集、5 题完整回答集、指标定义、运行口径与可复现边界 |
| [引用与溯源](citations.md) | 从回答编号到子块、父块、文档版本、Source Block 和原始资产 |

## 架构与运行

- [架构说明](architecture.md)：模块职责、运行拓扑、数据权威、恢复语义、安全边界和历史 Artifact 兼容。
- [开发指南](development.md)：本地依赖、后端、前端、Sidecar 与验证命令。
- [部署与恢复](deployment.md)：单机部署、健康检查、凭据轮换、可观测性和备份恢复。
- [展示素材](showcase/README.md)：来自公开 Demo 的桌面截图和只读再生成方式。

## 结果证据

- [Deep 200 题检索与证据报告](../benchmarks/deep-rag-final-200-case.md)：Recall、MRR、nDCG、AEC、RCC、延迟、Token 和失败续跑记录。
- [Fast / Deep 5 题完整回答报告](../benchmarks/fast-vs-deep-full-answer.md)：最终回答覆盖、语义正确性、引用支持和时延对照。
- [Auto 质量成本报告](../benchmarks/auto-routing-cost-quality.md)：成本优先与质量优先两个路由档位的真实取舍。
- [中文企业技术知识库 v1](../data/chinese-enterprise-rag-v1/README.md)：200 篇转换文档、440 题蓝图、来源清单、SHA-256 与许可。

## 阅读原则

1. 产品能力以当前源码和本文档为准；旧运行名不是现行架构的一部分。
2. 200 题主报告评价检索与证据阶段，不把省下的研究 Token 说成完整回答账单。
3. 已保存的旧评测 Artifact 仍可读取，所以数据库迁移和兼容查询中会出现历史标识；当前聊天编排不会调用这些旧实现。
4. 公开仓库包含完整语料，但代码许可与四个上游语料许可彼此独立。
