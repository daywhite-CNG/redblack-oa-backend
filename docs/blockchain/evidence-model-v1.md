# Evidence Contract v1：审批事实模型

状态：**P1 规范冻结；尚未实现或部署 OA 证据合约**。本目录五份规范和三个黄金向量共同定义 v1；与旧产品/API 文档中的示例冲突时，以本目录为准。P0 只验证最小链交互；P0.5 Docker 运行验收仍为 **BLOCKED**，不是 PASSED 或 FAILED。

## 范围与事实来源

一条证据对应一条已提交的 `approval_record`：`SUBMIT`、`RESUBMIT`、`APPROVE`、`REJECT`、`TRANSFER`、`WITHDRAW`。`CREATE`、草稿编辑、附件编辑和删除不产生 v1 证据。申请状态由 `leave_application.status` 表示；任务状态不代替申请状态。当前代码的规则见 [LeaveApplicationService](../../approval-service/src/main/java/com/redblack/approval/application/LeaveApplicationService.java)、[ApprovalTaskService](../../approval-service/src/main/java/com/redblack/approval/application/ApprovalTaskService.java)、[ApprovalRecordService](../../approval-service/src/main/java/com/redblack/approval/application/ApprovalRecordService.java)；本规范中的专用快照、摘要和链合约是目标契约，**不是当前已实现功能**。

| action | 前态 → 后态（申请） | submissionRound | 任务事实 |
|---|---|---|---|
| SUBMIT | DRAFT → PENDING | 0 → 1 | 建立本轮首个 PENDING 任务 |
| RESUBMIT | REJECTED/WITHDRAWN → PENDING | 上轮 +1 | 建立新轮 PENDING 任务 |
| APPROVE | PENDING → APPROVED | 不变，≥1 | 当前 PENDING 任务完成 |
| REJECT | PENDING → REJECTED | 不变，≥1 | 当前 PENDING 任务完成 |
| TRANSFER | PENDING → PENDING | 不变，≥1 | 旧任务 TRANSFERRED，同轮新任务 PENDING；允许重复转交 |
| WITHDRAW | PENDING → WITHDRAWN | 不变，≥1 | 当前 PENDING 任务 CANCELLED |

`submissionRound` 是申请累计提交次数，不是转交次数。只有 `SUBMIT`/`RESUBMIT` 增加；从 WITHDRAWN 重提合法。每次业务动作的权限、当前任务、目标办理人和乐观锁由 OA 校验；合约只校验上表所能表达的粗粒度状态机。

## 字段与归属

规范化证据负载的 17 个字段、类型、顺序和字节规则以 [canonicalization-v1.md](canonicalization-v1.md) 为准。核心含义：

| 字段 | 含义 |
|---|---|
| `schemaVersion` | 固定整数 `1`；独立于事件信封 `eventVersion` |
| `evidenceId` | 由业务摘要和审批记录 ID 确定的 SHA-256 标识，非随机发送 ID |
| `businessType` | 固定 `LEAVE_APPLICATION` |
| `businessIdHash` | 申请 ID 的带域分隔 HMAC-SHA-256 承诺；同申请恒定 |
| `pseudonymKeyId` | 链下 HMAC 密钥版本标识，v1 验真必须保留对应旧密钥 |
| `sourceRecordId` | 已持久化审批记录 ID 的无前导零十进制字符串；仅链下 |
| `action`、`fromStatus`、`toStatus` | 六种动作和申请状态；TRANSFER 两端均为 PENDING |
| `submissionRound` | 本条动作完成后的累计提交轮次 |
| `operatorSubjectHash` | OA 操作者用户 ID 的带域分隔 HMAC 承诺，非链签名者地址 |
| `targetSubjectHash` | 仅 SUBMIT、RESUBMIT、TRANSFER 为新办理人承诺，其他为 `null` |
| `taskIdHash` | 本动作建立或处理的任务 ID 承诺；六种动作都必填 |
| `newTaskIdHash` | 仅 TRANSFER 为新任务 ID 承诺，其他为 `null` |
| `occurredAt` | 同一动作的审批记录持久化 UTC 时间，毫秒精度 |
| `attachmentHashRoot` | 该动作时已冻结附件清单的 Merkle 根；空集也有确定摘要 |
| `previousEvidenceHash` | 同一业务按审批记录顺序紧邻上一条正式证据的 `evidenceHash`；首条全零，跨轮不重置 |

`previousEvidenceHash` **不是**上笔交易哈希、区块哈希、审计日志哈希或上一轮起点。后续证据不能因为前项上链延迟而改写已冻结负载；按业务顺序等待、提交、确认。规范化负载中的 `sourceRecordId` 不是链上字段。

旧草案示例里的 `businessVersion` 不进入 v1 规范化负载：它不是六动作的唯一事实标识，现有普通 Outbox 还可能读取更新前版本；v1 使用已持久化 `sourceRecordId` 和状态/轮次。旧草案 `organizationId` 也不进入 v1：当前只有 OA 部门等数据，不能把部门当联盟组织。若将来加入两者，须新版本，不得悄悄改 v1 字节。

## 隐私边界

链上字段白名单是 `evidenceId`、`businessIdHash`、`evidenceHash`、`previousEvidenceHash`、`attachmentHashRoot`、`schemaVersion`、`action`、`fromStatus`、`toStatus`、`submissionRound`、`occurredAtMillis` 和合约自产生的 `recordedBlockNumber`。`businessType` 由合约类型域固定为 `LEAVE_APPLICATION`。`operatorSubjectHash`、`targetSubjectHash`、任务承诺、`sourceRecordId`、`pseudonymKeyId` 仅在链下负载中，由 `evidenceHash` 统一承诺；`sourceEventId` 仅在内部事件信封/链下索引中。`hashAlgorithm` 固定 SHA-256，不是可变字段。

链上不保存申请 ID、审批记录 ID、任务 ID、姓名、部门、手机号、请假原因、审批意见、附件文件名、原文、对象存储地址、访问令牌或密钥。链下加密/受控保存规范化负载、审批记录、附件快照、回执与哈希键版本。摘要会暴露同一业务的关联和动作时间线；HMAC 用于阻止低熵数字 ID 被离线枚举，**不等于匿名化**。密钥不得进入 Git、事件正文、链、API 响应或日志；组织服务签名私钥与 HMAC 密钥是不同秘密。

当前 `office-service` 对上传原始字节计算小写 SHA-256 并在下载时复核，但内部 `FileSummary` 不返回该 SHA。当前 `approval-service` 只持有附件 ID，绑定还由事件异步完成。因此现有事件不能生成可信冻结附件清单；未来实现必须在业务事实提交时得到每个 `fileId/sha256/sizeBytes` 的可信不可变快照，否则该动作**不得**产生 v1 证据，不能事后读取“当前附件”冒充当时快照。此要求不意味着 P1 修改附件 API 或数据库。

## 版本

`schemaVersion=1` 锁定字段、编码、哈希、Merkle 和状态语义；未知版本必须隔离，不以 v1 猜测处理。未来变更使用新版本和新向量；历史证据仍按其原版本及原 HMAC 密钥验真。旧 API 文档的 `payloadSchemaVersion` 与本规范的 `schemaVersion` 指**同一个**负载版本，不产生第二套独立版本。合约规格见 [contract-spec-v1.md](contract-spec-v1.md)。

## 未解决的问题（不改变 v1 字节语义）

1. 如何在附件绑定异步、现有内部元数据不含 SHA 的现状下，于 OA 事务内获得可信、不可变的附件快照；在解决前不得上线专用证据事件。
2. 如何持久化同业务上一条源证据摘要及事务顺序，以便链尚未确认时仍可冻结下一条 `previousEvidenceHash`；不得靠 Kafka 到达顺序或当前可变 OA 行倒推。
3. HMAC 密钥由哪个受控运行组件持有、如何备份和保留历史版本，以及未来组织/多部署命名空间如何治理。v1 限定单一 OA 部署；这些运维选择不能改变已冻结的域前缀或旧证据字节。
4. 后续运行阶段的 writer 管理、合约地址/群组 ID、链下证据与回执存储、失败告警及 P0.5 Docker 运行验收尚未完成。P1 不把它们说成已验证。
