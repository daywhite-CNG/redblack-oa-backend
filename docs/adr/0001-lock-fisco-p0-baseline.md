# ADR 0001：锁定 P0 联盟链技术基线

- 状态：已接受（2026-09-19）
- 范围：Evidence Contract v1 的设计与后续兼容验证；不代表业务合约已部署

## 背景

[P0 实测报告](../research/fisco-poc.md)记录 Windows 上 Java 21 与四节点 FISCO BCOS 的两次完整最小交互。P0 的 `EvidenceRegistry.sol` 只验证节点连接、链高、部署、写入、查询、交易哈希与区块号，未验证 OA 权限和业务状态机。[P0.5 容器化报告](../research/fisco-docker-poc.md)的实现已完成但 Docker Engine 启动授权未执行，运行验收仍为 **BLOCKED**；不得把 P0 成功移作 P0.5 通过证据。

## 决定

P1 固定以下已实测/记录组合，不升级：

| 组件 | 锁定版本 |
|---|---|
| Java | Microsoft OpenJDK `21.0.9+10-LTS` |
| FISCO BCOS Air | `3.7.3` |
| Java SDK | `org.fisco-bcos.java-sdk:fisco-bcos-java-sdk:3.8.0` |
| Solidity 编译器 | solc-js `0.8.11`；合约 `pragma 0.8.11` |

Evidence v1 的字段、规范化字节、哈希及合约行为以 `docs/blockchain/` 五份规格和黄金向量为准。P0 POC 合约不改；`ApprovalEvidenceRegistry v1` 仅为 P1 冻结规格，尚无实现。正式业务合约与运行验收属于后续阶段，须先满足本 ADR 的版本组合及 P1 向量。

## 后果

好处是设计以实际 Java 21 闭环为基线，避免因未验证升级引入新的兼容变量。代价是需要在后续阶段单独验证 Docker 四节点、服务集成、合约部署、writer 管理和完整 OA 事件；P1 文档完成不等于这些运行验收完成。任何版本升级或 schema v1 变更需要新 ADR/版本和独立验证，不能静默改写历史证据。
