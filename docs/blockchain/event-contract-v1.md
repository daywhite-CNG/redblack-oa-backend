# EvidenceRequested v1：不可变事件快照契约

本文件规定**未来**的专用证据事件；P1 不创建 Kafka 主题、消费者、表或服务，不改变当前审批事务。当前 `OutboxService` 的 `LEAVE_SUBMITTED` 同时表示首次提交与重提，`eventVersion=1` 的普通业务负载包含展示信息、缺少文件 SHA/前序摘要，不能直接充当证据事件，也不能直接哈希。旧 `docs/接口文档.md` 中的 `EvidenceRequested` 是草案示例，以本文件为准。

## 发布边界

- 主题固定 `redblack.blockchain.evidence.v1`，事件类型固定 `EvidenceRequested`，信封 `eventVersion=1`；负载中的 `schemaVersion=1` 是**另一维**的规范化版本。旧 API 名称 `payloadSchemaVersion` 等于后者。
- 每条已成功提交的六种审批动作各产生且仅产生一个源记录和一个专用证据 Outbox 快照。`sourceEventId` 即信封 `eventId`（UUID），用于消息去重；`evidenceId` 由 `sourceRecordId` 和 `businessIdHash` 确定，用于逻辑证据去重。重发同一 Outbox 行不得重新生成任一 ID。
- 业务状态变更、审批记录插入、附件清单快照及证据 Outbox 写入必须在**同一 OA 数据库事务**成功或一同回滚。`approval_record.id` 和持久化的 `operated_at` 必须已确定；不能使用另一回时钟或更新前版本。上链在事务外异步发生，链失败不能回滚已提交 OA 事实。
- 新事件的 `aggregateType=LEAVE_APPLICATION`、`aggregateId` 和 Kafka message key 均为无前导零的申请 ID 十进制字符串；同业务固定同一 key。信封 `occurredAt` 由规范化负载的 `occurredAt` 解析而来，不额外取时钟。内部受控主题可以保有申请/操作者 ID 用于关联，但不能把它们、姓名、意见等转发上链或外部响应。
- 信封沿用 `EventEnvelope` 的必填字段：`eventId`（即 `sourceEventId`）、`eventType`、`eventVersion`、`occurredAt`、`producer=approval-service`、`aggregateType`、`aggregateId`、`requestId`、`traceId`、`actor.userId` 和 `payload`。`actor.userId` 是内部受控主题中的明文 OA ID，不是链上 `operatorSubjectHash`；`requestId/traceId` 也不得进入 canonical bytes。

## 负载（目标契约）

```json
{
  "schemaVersion": 1,
  "canonicalJson": "{...固定 17 字段的精确无空格 JSON...}",
  "evidenceHash": "64 位小写 hex",
  "attachments": [
    {"fileId": "101", "sha256": "64 位小写 hex", "sizeBytes": "12345"}
  ]
}
```

`canonicalJson` 必须能按 [canonicalization-v1.md](canonicalization-v1.md) 逐字节重建；外层 `schemaVersion` 必须等于内层 `canonicalJson.schemaVersion`。`evidenceHash` 是带域分隔的摘要，不能信任传入值而不重算。`attachments` 是该动作时的**不可变**原始文件摘要快照，按数值 fileId 升序，无重复；零附件为 `[]`。`sizeBytes` 用十进制字符串避免 JSON 数字精度问题。每个文件的 SHA 来自 office-service 对原始字节的已验证存储摘要；不同动作可因附件编辑而有不同根。消费者用附件快照重算根，再与 `canonicalJson.attachmentHashRoot` 比对；缺失、状态不可用或哈希不符应隔离，不能以事后当前文件/附件关系补齐。

生产者还须检查 `canonicalJson` 中的 action、round、状态、任务/目标承诺和 `previousEvidenceHash` 与同事务业务事实相符；`sourceRecordId` 指新审批记录。首次前序为零；后项前序为**上一条源证据摘要**，即使上一条尚未上链也不变化。当前系统没有此类不可变源快照或 head，因此这是未来实现的前置能力，不可误称已经满足。源记录顺序以同业务受锁事务的动作顺序为准；时间戳只是事实字段，不用来单独决定顺序。

## 投递、消费与恢复

- Outbox 至少一次投递；同一业务 key 保证分区顺序，但消费者仍以 `sourceEventId` 幂等，并以 `evidenceId` 做逻辑唯一检查。相同 ID、不同字节/摘要是冲突，必须隔离告警，不能“最后写入覆盖”。
- 消费者验证版本、白名单、附件根、规范化字节、摘要和前序引用；未知版本/动作、缺字段、乱序或源事实不一致进入隔离/等待，不猜测修复。
- 后续证据可先到达或在前项回执未知时到达，但只能**等待**前项链上确定。不能因等待或重试重写原 `canonicalJson`、`evidenceHash` 或 `previousEvidenceHash`。前项链上已存在时先查询并核对，再继续；重复发送仍用同一不可变快照。
- 链交易回执的 `transactionHash`、`blockNumber` 是派生执行结果，**不属于**源事件或规范化摘要。回执未知不能当失败或再造一条证据。

安全边界：事件主题、Outbox 和链下快照必须受访问控制；不把 HMAC 密钥、节点签名私钥、证书或文件原文写入事件。当前 office 内部 `FileSummary` 不含 SHA，附件绑定又异步发生；在可信同步快照/一致性方案落地前，专用事件**不可上线**。P1 只冻结所需结果，不擅自选定跨服务事务或数据库变更方案。
