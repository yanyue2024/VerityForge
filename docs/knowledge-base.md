# 知识库建设与管理

知识库不是向量文件夹，而是一条从原始资产到可检索证据的受控发布流程。VerityForge 将文档版本、来源 Metadata、解析结果、父子 Chunk、向量代际和处理日志放在同一业务模型中。

## 当前公开语料

公开版提交了完整的 [中文企业技术知识库 v1](../data/chinese-enterprise-rag-v1/README.md)：

| 来源 | 文档数 | 主要领域 | 许可 |
| --- | ---: | --- | --- |
| openEuler | 50 | 企业 Linux 与系统运维 | CC BY-SA 4.0 |
| Kubernetes | 50 | 云原生与集群管理 | CC BY 4.0 |
| Ant Design | 50 | 企业产品与交互组件 | MIT |
| Apache Doris | 50 | 数据平台与分析 | Apache-2.0 |

200 个选定来源分别转换为 PDF、DOCX、HTML、Markdown，各 50 篇。仓库还保存上游 Commit、原始路径、Source SHA-256、转换文件 SHA-256、来源摘要和许可副本，因此公开数据不依赖另一个私有仓库或旧发行版。

![中文企业技术知识库 v1 文档列表](showcase/knowledge-documents.png)

## 入库状态机

```mermaid
flowchart LR
    UPLOAD["上传与哈希校验"] --> PARSE["格式解析"]
    PARSE --> NORMALIZE["正文规范化"]
    NORMALIZE --> QUALITY["结构与质量检查"]
    QUALITY --> CHUNK["父子分块"]
    CHUNK --> EMBED["Embedding 与代际索引"]
    EMBED --> PUBLISH["事务发布"]
```

每个阶段单独持久化输入、输出、状态、时间和错误。失败任务从首个未成功阶段恢复；同一消息被重复消费不会重复推进已经成功的阶段。发布只发生在解析、分块与索引都完成之后，未发布版本不会进入检索范围。

![文档处理阶段](showcase/document-processing.png)

## 多格式解析

系统支持 PDF、DOCX、XLSX、HTML、Markdown 与纯文本。解析结果统一为保留标题层级、列表、表格和 Source Block 的规范化文档：

- Java 解析器负责常规格式与基础抽取。
- 可选 Parser Sidecar 负责更复杂的 PDF、OCR 与版面场景。
- 原始文件、解析正文和渲染 Markdown 分开保存，避免展示格式覆盖事实来源。
- 质量报告记录解析覆盖、空内容、结构异常和人工复核状态。

![解析正文与章节目录](showcase/document-parsed.png)

## 父子 Chunk

当前稳定策略：

| 层级 | 目标 Token | 最大 Token | 重叠 | 用途 |
| --- | ---: | ---: | ---: | --- |
| 父块 | 1,000 | 1,200 | 100 | 保留章节语义、Deep Read、最终回答上下文 |
| 子块 | 250 | 384 | 40 | 关键词与向量精确召回、引用定位 |

切分优先尊重章节、段落、列表与表格边界；超长结构才继续按 Token 预算拆分。每个子块保存父块 ID、章节路径、文档版本、Source Block 和位置范围。命中子块后可以直接定位父块，而不需要依赖标题字符串猜测上下文。

![子块与父块上下文](showcase/document-chunks.png)

## 两层 Metadata

文档级 Metadata 用于治理和过滤，包括：

- 数据集、文档别名、来源项目与业务领域；
- 原始格式、上游版本、许可和来源 URL；
- 组织、部门、分类、生效时间、失效时间与使用状态；
- Parser Schema、Chunk Policy 和 Index Generation。

Chunk 级 Metadata 用于检索与溯源，包括文档版本、章节路径、父块关系、Source Block、页码或字符位置、Token 数和渲染内容。Metadata Schema 本身带版本历史；修改过滤字段不会回写篡改已经发布的文档版本。

![文档 Metadata](showcase/document-metadata.png)

## 版本和索引代际

逻辑文档可以有多个不可变版本，但同一时刻只有 `current_version_id` 参与默认检索。新版本准备完成后以事务切换当前指针；旧版本继续支持历史引用。Embedding 模型变化时构建新的 Index Generation，旧代际在新代际覆盖完整并激活前持续提供查询。

这两个边界解决不同问题：文档版本保证内容生命周期，索引代际保证检索基础设施升级。二者不会互相冒充。

## 数据验证

完整语料可以离线验证：

```bash
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
```

验证器检查 200 个文件、四种格式数量、Manifest、SHA-256、来源映射、许可文件和 440 题评测蓝图。重新构建需要访问固定的上游 Commit；日常验证不需要联网。
