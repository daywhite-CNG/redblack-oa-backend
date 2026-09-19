# ApprovalEvidenceRegistry v1：合约规格（未实现）

这是 Solidity **0.8.11** 目标合约的 ABI/行为规格，**不是** P0 的 `tools/fisco-poc/contracts/EvidenceRegistry.sol`，也不声称已部署。P0 合约只证明部署、读写和回执闭环。FISCO BCOS 3.7.3 / Java SDK 3.8.0 / Java 21.0.9 基线由 [ADR 0001](../adr/0001-lock-fisco-p0-baseline.md) 锁定。

## 编码与持久化

链上摘要/ID 为 `bytes32` 原始 SHA-256 或 HMAC-SHA-256 字节，不是十六进制文本。`businessType` 固定 `LEAVE_APPLICATION`，合约地址即 v1 类型域。`schemaVersion` 参数必须为 `1`。动作 `uint8` 编码：`1 SUBMIT, 2 RESUBMIT, 3 APPROVE, 4 REJECT, 5 TRANSFER, 6 WITHDRAW`。申请状态 `uint8` 编码：`0 DRAFT, 1 PENDING, 2 APPROVED, 3 REJECTED, 4 WITHDRAWN`。不接受其他值。

```text
RecordInputV1 {
  bytes32 evidenceId; bytes32 businessIdHash; bytes32 evidenceHash;
  bytes32 previousEvidenceHash; bytes32 attachmentHashRoot;
  uint8 schemaVersion; uint8 action; uint8 fromStatus; uint8 toStatus;
  uint32 submissionRound; uint64 occurredAtMillis;
}
RecordV1 = RecordInputV1 + uint64 recordedBlockNumber

recordEvidence(RecordInputV1 input) -> void
getEvidence(bytes32 evidenceId) -> (bool exists, RecordV1 record)
evidenceExists(bytes32 evidenceId) -> bool
getEvidenceIds(bytes32 businessIdHash, uint256 offset, uint256 limit)
  -> (bytes32[] evidenceIds, uint256 total)
setWriter(address writer, bool allowed) -> void  // 仅管理员
```

`recordedBlockNumber` 由合约以 `block.number` 写入，调用方不得指定。实现可用等价 ABI struct 但上述字段名、类型和语义不得变。不存在的 `getEvidence` 返回 `exists=false` 加零值结构，不通过异常伪造“查无”。时间是规范负载 UTC 毫秒的 Unix epoch 数；合约不验证它是否真实、也不用它排序。`transactionHash` 只能从 SDK/链交易回执取得，**不能**由合约内部生成或作为记录入参。

按 `evidenceId` 存不可变 Record；按 `businessIdHash` 保存插入顺序的 ID 数组与最后摘要/状态/轮次。`getEvidenceIds` 的 `limit` 必须为 `1..100`，`offset >= total` 返回空数组和总数，不做无界全量返回。查询是链上顺序而非时间排序。

## 写入原子校验

1. `msg.sender` 是管理员已授权 writer；管理员由部署时显式指定且不能是零地址。`setWriter` 只有管理员可调用，并发出 `WriterAuthorizationChanged(writer, allowed)`。OA 用户不持有 writer 私钥；writer 只证明服务账户签名，**不证明**真正业务操作者或组织身份。
2. `evidenceId`、`businessIdHash`、`evidenceHash`、`attachmentHashRoot` 均非零；`evidenceId` 从未登记。`schemaVersion=1` 且枚举有效。合约无法直接证明这些摘要按链下规范正确计算，因此调用方和验真方都须重算。
3. 首条证据只允许 `SUBMIT / DRAFT → PENDING / submissionRound=1 / previousEvidenceHash=0x00…00`。
4. 后续必须 `previousEvidenceHash == 当前 businessIdHash 的 latest evidenceHash` 且 `fromStatus == 上条 toStatus`；前序摘要不按轮次重置。`SUBMIT` 不能再次出现。
5. `RESUBMIT` 只允许上一状态 `REJECTED` 或 `WITHDRAWN` → `PENDING`，轮次恰为上一轮 +1；`APPROVE`、`REJECT`、`WITHDRAW` 从 `PENDING` → 对应终态，轮次不变；`TRANSFER` 从 `PENDING` → `PENDING`，轮次不变，可多次。非 `RESUBMIT` 后续动作轮次均等于上一轮，且 ≥1。
6. 任一检查不通过则整个调用回滚，不写半条证据、不追加时间线、不发成功事件。相同 `evidenceId` 的重试先链上查询并比对；合约本身拒绝重复，而不是静默覆盖。

链无法验证 OA 当前任务、转交目标权限、附件原始内容、审批意见或操作者身份；规范负载中的目标/任务承诺只由摘要覆盖，链上不解释。链也无法辨别“合法 writer 提交虚假 OA 快照”，必须结合链下原始审批记录、附件快照和权限审计验真。

## 事件

成功记录发出一次 `EvidenceRecorded(bytes32 indexed evidenceId, bytes32 indexed businessIdHash, bytes32 evidenceHash, bytes32 previousEvidenceHash, uint8 action, uint32 submissionRound)`；交易收据提供 `transactionHash` 与 `blockNumber`，事件自身不用重复二者。事件不带姓名、申请/任务明文 ID、文件名、附件列表、意见或密钥。读取者必须以合约存储为准，不能只凭日志断言记录存在。

本规格仅定义**单一 OA 部署命名空间**和管理员授权 writer；旧产品草案提到的多组织 `organizationId` 校验缺乏当前可靠组织编码与治理来源，不能伪装已经支持。若未来必须多组织共享合约，应另行设计版本/部署治理，不得把部门 ID 冒充联盟组织 ID。链部署地址、群组 ID、writer 管理和密钥托管在运行实现前确定，P1 不创设账户。
