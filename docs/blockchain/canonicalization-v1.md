# Canonical Evidence Payload v1

本文件定义**字节协议**，不能用普通 Jackson `Map`、对象属性顺序或当前 Outbox JSON 代替。对同一已冻结业务事实，任何语言实现都必须输出完全相同的 UTF-8 字节及 SHA-256。规范依据是本文件和 [三个黄金向量](test-vectors/)；不是宣称现有服务已实现。

## 输入约束与标识

所有 ID 均为正的有符号 64 位整数，转换成无前导零的 ASCII 十进制字符串（`1` 至 `9223372036854775807`）；不得接受 `+`、空格或指数形式。`businessType` 固定 `LEAVE_APPLICATION`。六个动作与五个申请状态用大写枚举名，禁止别名。`schemaVersion` 固定 JSON 整数 `1`；`submissionRound` 为 JSON 整数 `1..2147483647`。`pseudonymKeyId` 匹配 `[a-z0-9-]{1,32}`，作为链下密钥版本索引；同一业务时间线不能在 v1 中更换密钥，历史密钥必须可用于历史验真。HMAC 密钥是每个 OA 部署唯一、至少 256 位的安全随机字节，测试向量公开测试键绝不可部署使用。

以下 ASCII 字符串的 `\0` 表示**一个 0x00 字节**，不是反斜杠与数字零。`HMAC(K,x)` 为 HMAC-SHA-256，`SHA256(x)` 为 FIPS 180-4 SHA-256，`raw32` 为 32 原始字节；展示时一律用无 `0x` 前缀的小写 64 位十六进制。

```text
businessIdHash = HMAC(K, ASCII("RB-OA-BUSINESS-ID-V1\0LEAVE_APPLICATION\0") || ASCII(applicationId))
operatorSubjectHash = HMAC(K, ASCII("RB-OA-USER-ID-V1\0") || ASCII(operatorUserId))
targetSubjectHash = HMAC(K, ASCII("RB-OA-USER-ID-V1\0") || ASCII(targetUserId))
taskIdHash = HMAC(K, ASCII("RB-OA-TASK-ID-V1\0") || ASCII(taskId))
newTaskIdHash = HMAC(K, ASCII("RB-OA-TASK-ID-V1\0") || ASCII(newTaskId))
evidenceId = SHA256(ASCII("RB-OA-EVIDENCE-ID-V1\0") || raw32(businessIdHash) || uint64be(sourceRecordId))
```

`targetSubjectHash` 仅 SUBMIT、RESUBMIT、TRANSFER 非空。SUBMIT/RESUBMIT 的 `taskIdHash` 是新建任务；其他动作是处理的当前任务。`newTaskIdHash` 仅 TRANSFER 非空，且不同于 `taskIdHash`。TRANSFER 的 target 是新任务办理人；SUBMIT/RESUBMIT 的 target 是新建任务办理人。其他动作二字段必须为 JSON `null`，不能省略或用空字符串。所有 ID 哈希（含 evidenceId）仍可能产生关联性，不可解释为匿名化。

## 附件快照与 Merkle 根

动作提交时冻结附件集合。每个元素恰好含 `fileId`（上述正整数）、`sha256`（office-service 对**原始文件字节**的 SHA-256，小写 64 hex）和 `sizeBytes`（`0..9223372036854775807`）。文件必须在可信存储中为可用状态；不得用 ETag、文件名、对象键、压缩后字节、当前附件关系或消费者事后查询替代。按 `fileId` **数值升序**排序，重复 `fileId` 拒绝。附件清单只保存在受控链下快照，不作为规范化 JSON 的数组字段；其根进入负载。内容相同而 ID 不同的附件得到不同叶子。

```text
leaf[i] = SHA256(0x00 || ASCII("RB-OA-ATTACHMENT-LEAF-V1\0")
                 || uint64be(fileId[i]) || raw32(sha256[i]) || uint64be(sizeBytes[i]))
parent = SHA256(0x01 || ASCII("RB-OA-ATTACHMENT-NODE-V1\0") || raw32(left) || raw32(right))
emptyRoot = SHA256(0x02 || ASCII("RB-OA-ATTACHMENT-EMPTY-V1\0"))
```

`uint64be` 为固定 8 字节无符号大端，输入范围仍受上文有符号 64 位限制。每层从左到右配对；奇数末节点**原样上提**，不复制也不重新哈希，直到只剩一节点。零附件根为 `emptyRoot`，单附件根为其 leaf。`attachmentHashRoot` 始终是 64 位小写 hex，绝不为 `null`。这是自定义 v1 Merkle 树，**不是** RFC 6962 兼容性声明。改变排序、奇数规则、叶前缀或清单即改变根。

## JSON 精确表示

仅以下 **17 个键**，按下列顺序连续写出；每个键始终出现。对象没有多余空格、换行或 BOM，键和值之间仅 `:`，字段之间仅 `,`。字符串均通过以上枚举、ID、时间、hash 正则校验为 ASCII，故无 Unicode 转义、斜线转义或键排序的语言差异；控制字符、非 ASCII 和未知字段直接拒绝。布尔、浮点、指数、负数、`-0` 均不合法。仅 `targetSubjectHash` 与 `newTaskIdHash` 可为 JSON `null`。摘要串无 `0x` 前缀。

```text
schemaVersion,evidenceId,businessType,businessIdHash,pseudonymKeyId,
sourceRecordId,action,submissionRound,fromStatus,toStatus,
operatorSubjectHash,targetSubjectHash,taskIdHash,newTaskIdHash,
occurredAt,attachmentHashRoot,previousEvidenceHash
```

`occurredAt` 取同一动作**持久化后的** `approval_record.operated_at`，按 UTC 解释并输出固定格式 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`（恰好 3 位毫秒、四位年份 `0001..9999`、有效公历时间、无闰秒）；拒绝未明确时区的输入。数据库当前为 `DATETIME(3)`；先分别读取时钟或先哈希纳秒、再落库截毫秒会破坏可重复性。`sourceRecordId` 是持久化审批记录的 ID 十进制**字符串**。`previousEvidenceHash` 首条为 64 个 `0`；以后为该业务紧邻前一正式证据的 `evidenceHash`，不得为 `null`。时间、轮次、前序与附件在同一不可变快照冻结。

JSON 模板（尖括号仅表示占位，实际没有尖括号）：

```json
{"schemaVersion":1,"evidenceId":"<hex64>","businessType":"LEAVE_APPLICATION","businessIdHash":"<hex64>","pseudonymKeyId":"<ascii>","sourceRecordId":"<decimal>","action":"<ACTION>","submissionRound":1,"fromStatus":"<STATUS>","toStatus":"<STATUS>","operatorSubjectHash":"<hex64>","targetSubjectHash":null,"taskIdHash":"<hex64>","newTaskIdHash":null,"occurredAt":"2026-09-19T00:00:00.000Z","attachmentHashRoot":"<hex64>","previousEvidenceHash":"<hex64>"}
```

`canonicalBytes = UTF8(该无空格 JSON)`；`evidenceHash = SHA256(ASCII("RB-OA-EVIDENCE-V1\0") || canonicalBytes)`。它不是裸 `SHA256(canonicalBytes)`。链上 SHA-256 摘要使用 `bytes32` 原始字节；文档/API 使用小写 hex。链上不重建或解析 JSON，消费者负责校验/重算。

黄金向量的 `canonicalJson` 是**精确 ASCII 字符串**，其 UTF-8 编码即规范字节；另固定 `canonicalUtf8Length`、各级 Merkle 摘要和 `evidenceHash`。实现必须逐字节比对；还应验证附件输入乱序但根相同、相同时刻不同时区输入归一化相同、任一字节变化哈希不同、未知字段/缺字段/错误大小写/超界整数被拒绝。规范参考：[SHA-256](https://csrc.nist.gov/pubs/fips/180-4/upd1/final)、[HMAC](https://www.rfc-editor.org/rfc/rfc2104)；本文件的固定字段协议优先于一般 JSON 序列化实现。
