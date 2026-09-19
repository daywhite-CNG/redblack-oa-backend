# P1 领域词汇：审批存证

本文件只记录 P1 已冻结的词义；完整字段和字节协议见 [docs/blockchain/evidence-model-v1.md](docs/blockchain/evidence-model-v1.md) 与 [canonicalization-v1.md](docs/blockchain/canonicalization-v1.md)。这些是设计契约，不代表链服务已经实现。

| 术语 | 含义 |
|---|---|
| 业务事实 | OA 一次成功提交且有审批记录的六种动作之一；不是 Kafka 投递或链交易 |
| 证据 | 一个业务事实的不可变规范化负载及其摘要；`sourceRecordId` 唯一定位源审批记录 |
| 提交轮次 | 一份申请累计成功提交次数；转交、同意、驳回、撤回不增加 |
| 前序证据哈希 | 同申请紧邻上一条正式证据的 `evidenceHash`，首条全零；不随轮次重置 |
| 附件快照 | 动作时可信冻结的原文件 SHA-256、大小和文件 ID 集合，不是事后当前附件 |
| 链上承诺 | 合约保存的不透明 ID/摘要与粗粒度状态，不包含 OA 明文事实 |
| 验真 | 用原始版本与冻结链下快照重算后，同链上承诺只读比较；不等于证明 OA 事实本身真实 |
