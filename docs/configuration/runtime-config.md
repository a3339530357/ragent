# 运行时配置中心（第一期）

后台「系统配置」新增运行时配置编辑区。配置发布对下一次问答生效，正在执行的问答、Agent 工具循环和流式响应保留开始时的版本。

## 首次部署

1. 备份数据库，执行 `resources/database/upgrades/v2.0.0/261105_runtime_config.sql`。只需执行这个升级脚本，不要对现有数据库执行完整初始化脚本。脚本可以重复执行，不回填或修改业务数据。
2. 通过部署环境设置 `RAGENT_RUNTIME_CONFIG_MASTER_KEY`，值为 Base64 编码的 32 字节随机密钥。可以使用 `openssl rand -base64 32` 生成，并保存到部署平台的秘密配置中；不要提交到 Git。
3. 集群各实例使用相同的数据库、Redis、加密主密钥及部署基线（引擎模式、Embedding 等只读配置）。更换引擎模式或只读配置时，需要同时处理已发布快照的兼容性，本期不自动迁移这些设置。首次部署需要重启服务，使新代码及表结构生效；后续本期开放的设置无需重启。
4. 重新构建并部署前端。在「系统配置」编辑、保存草稿、校验后发布。

主密钥必须持久保存，后续部署沿用同一把。此版本不提供主密钥轮换工具。没有主密钥时，可以运行未发布过后台配置的 YAML 默认配置，但管理接口禁止保存、发布、回滚；已有加密发布版本却缺少或使用错误密钥时，服务拒绝启动，避免静默回退到其他模型。

配置中心表不可用时也拒绝启动；运行中的服务若刷新失败，会继续使用最后成功加载的版本并报告错误。

## 可编辑范围

| 分组 | 可编辑项 |
| --- | --- |
| 主 Agent | `agent.chat` 对应的供应商、远端模型名、思考强度 |
| 模型供应商 | 地址、密钥、接口路径、现有思考参数方言；新增通用 OpenAI 兼容聊天供应商 |
| 辅助模型 | Chat 注册表与 fast/standard/deep 档位、超时和顺序；Rerank 和 VLM 模型 |
| 问答功能 | 查询改写、重排序、上下文富化、引用 |
| 检索策略 | TopK、召回预算、作用域阈值、已装配通道的启停／权重、融合和证据相关性过滤 |

Embedding、熔断与输出策略、数据库／Redis／存储／向量后端、引擎类型、上传限制、记忆和限流参数保持部署配置。现有智能体提示词管理保持独立。

密钥轮换后，在途请求仍使用旧凭据；应等这些请求结束后再在供应商侧撤销旧密钥。

Embedding 使用的供应商可以轮换密钥，但不能删除或修改地址、接口路径及协议方言。更换嵌入模型（即使维度相同）需要专门迁移／重建向量索引，本期不开放。

现有 Chat 专用适配器继续复用。新增普通供应商可以执行 OpenAI 兼容 Chat／VLM 调用，不会因此自动获得 Embedding 或 Rerank 适配器。Rerank 仅能选择已经实现的供应商。供应商已被主 Agent 或模型候选引用时不能删除；档位必须有可用候选。主 Agent 的远端模型名独立于辅助 Chat 注册表，不强制使用同一个默认模型。

## 发布规则

- 第一次发布前，使用 YAML／环境变量。第一次发布会将当前有效配置复制为完整快照；随后以数据库发布版本为准，不会在重启时被 YAML 自动覆盖。
- 全部管理员共用一份草稿。保存、发布、回滚携带 `draftRevision` 和 `baseVersion`；其他管理员已修改时拒绝覆盖，刷新后重新编辑。
- 保存草稿不影响运行。校验及准备客户端均不调用外部模型。模型连接测试必须手动点击，使用固定文本；VLM 使用固定测试图片，不发送用户问题、知识库正文或聊天历史，可能产生供应商调用费用。
- 发布前校验模型引用、启用状态、协议、检索预算和跨字段约束；先准备客户端，再事务写入版本及发布指针，提交成功后原子切换本机配置。
- Redis 通知只含版本号；每 5 秒核对数据库版本以补偿漏通知。各实例独立应用，不保证集群同一时刻切换。页面显示每个实例的目标版本、应用版本和错误；失联实例心跳 30 秒后过期。
- 回滚会校验历史配置并生成新版本，清除共享草稿，不修改历史记录。「恢复部署默认配置」以当前进程启动时读取的部署配置为基础生成新版本。
- 本地快照仅被活动任务引用；通用 Chat 客户端共享容器 HTTP 连接池，超时派生缓存有界。AgentScope 模型使用 SDK 管理的共享 transport，没有公开 `close` 方法；旧快照及缓存 Agent 失去引用后由 GC 回收，不会在中途关闭共享连接池。

## API

管理路径统一为 `/rag/settings/runtime`，每个接口都检查管理员角色，并经过现有登录和演示只读拦截。返回值沿用项目的 `Result` 包装与业务错误格式。

| 方法及路径 | 用途 |
| --- | --- |
| `GET /rag/settings/runtime` | 发布配置、草稿、版本号、可写状态、变更字段及实例状态 |
| `PUT /rag/settings/runtime/draft` | 保存共享草稿：`draftRevision`、`baseVersion`、`config` |
| `POST /rag/settings/runtime/validate` | 校验已保存草稿：`draftRevision`、`baseVersion`；返回错误列表 |
| `POST /rag/settings/runtime/publish` | 发布已保存草稿：`draftRevision`、`baseVersion` |
| `POST /rag/settings/runtime/rollback` | 发布历史配置：另传 `version`，0 表示部署默认配置 |
| `GET /rag/settings/runtime/versions` | 最近 50 个发布版本的元数据 |
| `GET /rag/settings/runtime/versions/{version}` | 查询脱敏后的历史配置 |
| `POST /rag/settings/runtime/test` | 测试草稿或有效配置：版本参数、`capability`（agent/chat/rerank/vlm）、`modelId`（主 Agent 可空） |

`config` 是完整的分组对象：`ai`、`ragFeatures`、`ragSearch`，Agent 引擎另有 `agentChat`。不接受未开放字段。响应中的 `effective` 表示数据库目标版本，`appliedVersion` 是响应实例已应用版本，两者可能暂时不同。

`apiKey` 在线路上使用明确动作对象：

```json
{"action":"keep","configured":true}
```

保留密钥时使用 `keep`，替换时提交 `{"action":"replace","value":"新密钥"}`，清除时提交 `{"action":"clear"}`。`configured` 只用于展示。查询接口从不返回原密钥；服务端基于明文比较生成变更字段列表，因此保存后仍可看到密钥发生过修改，但看不到值。

草稿及历史版本整份使用 AES-256-GCM 加密，随机 nonce 和认证标签校验完整性。发布、回滚、保存、测试沿用业务审计；审计不存请求正文。模型请求 dump 隐藏 Authorization、API Key 和 Cookie 等鉴权头。

原 `GET /rag/settings` 保留已有字段并增加 `runtimeConfigVersion`，显示本机有效版本下的配置。

## 验证

后端采用单元测试和本地 HTTP stub，不依赖真实模型或现有业务数据库。测试覆盖配置冲突、加解密、重启加载、回滚、失败保留旧版、通知补偿、模型地址与密钥切换、熔断隔离、线程池与 Reactor 上下文传播、权限和转储脱敏。

```bash
./mvnw -q -pl agent -am test \
  -Dtest=RuntimeConfigSpringStartupTest,RuntimeConfigServiceTest,RuntimeConfigCipherTest,RuntimeConfigSecretsTest,RuntimeConfigControllerTest,RuntimeConfigSyncTest,RuntimeConfigContextTest,AIModelRuntimeTest,RuntimeRagConfigurationTest,RuntimeAgentConfigTest,RuntimeSecretDumpTest,ModelRequestDumpInterceptorTest,ReActAgentProviderTest,AgentMemoryJudgeTest,AgentConversationSummarizerTest,ChatTierConfigValidatorTest,RoutingLLMServiceHalfOpenRecoveryTest,BaiLianRerankClientTest,EvidenceGatePostProcessorTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.skip=true

npm --prefix frontend run test:runtime-settings
```

前端交互测试使用 jsdom 和模拟管理接口，覆盖编辑、草稿、校验、发布确认、并发冲突、密钥替换、只读状态、回滚与显式连接测试。真实 PostgreSQL 迁移、真实模型访问及真实多实例部署需要部署时再验收。

本次交付验证：后端相关测试 113 项、前端交互测试 8 项通过，TypeScript、修改文件的 ESLint 和 `git diff --check` 通过。未执行实际数据库升级、调用真实模型或操作现有服务进程。

启动兼容性补充：默认配置按贡献器声明的数据类型序列化，避免 Spring CGLIB 的 `$$beanFactory` 等内部属性进入配置。真实 Spring 容器测试覆盖首次启动、加载已发布版本和只读配置数字值的往返比较；本次修复后 30 项相关测试通过。
