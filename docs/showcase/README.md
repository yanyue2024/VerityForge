# VerityForge 桌面展示素材

这里保存公开作品集使用的稳定截图。所有画面来自 [18306 公开 Demo](http://idcmnt1.truesight.com.cn:18306) 的真实桌面 Web、中文企业技术知识库 v1 和专门置顶的展示会话，不使用移动端布局。

## 核心画面

| 文件 | 展示内容 |
| --- | --- |
| `chat-deep-evidence.png` | Deep 回答、置顶会话、Goal 关联、召回子块和父块证据 |
| `chat-fast-evidence.png` | Fast 回答、编号引用和子块证据 |
| `chat-auto-evidence.png` | Auto 实际选择后的回答与证据 |
| `knowledge-bases.png` | 知识库总览、200 篇文档与 6,201 个子块 |
| `knowledge-documents.png` | 中文企业技术知识库 v1 的 200 篇文档列表 |
| `evaluation-fast-deep.png` | Fast / Deep 完整链路对照结果；`V8 FINAL` 为已保存运行的原始审计标签 |

## 文档工作区

| 文件 | 展示内容 |
| --- | --- |
| `document-original.png` | 原始资产预览 |
| `document-parsed.png` | 解析正文和章节目录 |
| `document-chunks.png` | 子块、Token、章节路径和可展开父块 |
| `document-metadata.png` | 来源、许可、格式、上游版本和业务 Metadata |
| `document-processing.png` | 解析、分块、Embedding、发布等处理阶段 |

这些截图保留真实产品密度，不做营销式拼贴。根 README 默认展示 Deep、知识库、Fast、Auto 和评测五张，其余素材放在专题文档中按需展开。

## 重新生成

截图脚本只读取现有页面，不新建对话、不上传文档、不修改置顶状态：

```bash
RAG_SHOWCASE_BASE_URL=http://idcmnt1.truesight.com.cn:18306 \
RAG_SHOWCASE_USERNAME='<demo-user>' \
RAG_SHOWCASE_PASSWORD='<demo-password>' \
RAG_SHOWCASE_BROWSER_PATH=/usr/bin/google-chrome \
npm --prefix web run showcase:capture
```

也可用短期 Access Token，并由脚本从 JWT Claim 读取用户、组织、角色和有效期：

```bash
RAG_SHOWCASE_BASE_URL=http://idcmnt1.truesight.com.cn:18306 \
RAG_SHOWCASE_ACCESS_TOKEN='<short-lived-token>' \
RAG_SHOWCASE_DISPLAY_NAME='<display-name>' \
RAG_SHOWCASE_BROWSER_PATH=/usr/bin/google-chrome \
npm --prefix web run showcase:capture
```

输出目录默认是 `docs/showcase`，可用 `RAG_SHOWCASE_OUTPUT_DIR` 覆盖。固定视口为 2048x1080、浅色主题、Reduced Motion，浏览器外框不会进入图片。

## 发布边界

- 只展示桌面 Web，不维护移动端作品截图。
- Demo 是共享 HTTP 环境，可能维护或更新；不要上传私密文档、生产凭据或执行压力测试。
- 截图不能包含 API Key、JWT、密码、内部文件路径或未公开组织数据。
- 展示会话与知识库内容必须来自已公开许可语料。
- 评测画面如实保留已保存运行的标签；`V8 FINAL` 仅标识产生报告的原始运行，公开源码中的同一最终实现已收束为 `deep-rag-final`。
- 发布 GitHub 仓库不更新、重启或替换 18306 的现有构建。
- Demo 不可用时，以仓库截图和评测报告为稳定作品证据。
