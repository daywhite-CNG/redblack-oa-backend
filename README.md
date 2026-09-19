# Red&Black OA Backend

《基于联盟链的小型企业 OA 协同办公系统设计与实现》的后端工程。

本项目以请假审批为业务载体，保留身份、RBAC、办公、审计和异步事件能力，并规划新增独立联盟链证据服务，实现审批关键事实的可信存证、追溯、验真和故障恢复。

> 当前状态说明：仓库现有代码已经包含 OA 微服务、Outbox/Inbox、Kafka、OSS 与测试材料，但尚未包含 `blockchain-service`、联盟链节点或智能合约实现。文档中的联盟链部分是毕业设计 V2 开发基线，不代表已经完成或验证。

## 项目目录

```text
redblack-oa-backend/
├── gateway-service/       # 网关、认证前置检查和路由
├── identity-service/      # 登录、用户、部门、角色、菜单和权限事实源
├── approval-service/      # 请假申请、审批任务、记录和业务状态机
├── office-service/        # 工作台、公告、通知和 OSS 附件
├── audit-service/         # 操作日志消费、存储和查询
├── redblack-common/       # 稳定技术契约，不共享业务实体
├── contracts/             # 当前 OpenAPI；后续增加智能合约源码和部署产物
├── deploy/                # 本地/虚拟机部署与验收脚本
├── docs/                  # 当前产品、权限和接口基线
└── evidence/              # 历史执行证据，不代替当前验证
```

目标阶段将新增：

```text
├── blockchain-service/    # 证据消费、规范化、SDK、回执、验真和补偿
└── contracts/solidity/    # ApprovalEvidenceRegistry 等智能合约
```

## 产品边界

### OA 业务

- 用户、部门、角色、菜单和数据权限。
- 请假草稿、提交、撤回、重新提交、同意、驳回和转交。
- 工作台、公告、通知、附件和操作日志。
- MySQL、Redis、Kafka Outbox/Inbox 与 OSS 文件完整性。

### 联盟链主线

- 对 `SUBMIT`、`RESUBMIT`、`APPROVE`、`REJECT`、`TRANSFER`、`WITHDRAW` 形成证据。
- 使用版本化规范负载、SHA-256、附件哈希根和前序证据哈希。
- 通过智能合约完成证据唯一性、联盟组织权限和状态迁移校验。
- 保存交易哈希、区块高度、合约地址和确认时间。
- 支持链上链下验真、重复事件幂等、回执未知恢复和链中断补偿。

系统不将附件原文、姓名、请假原因或审批意见明文上链，也不为每个 OA 用户托管链私钥。

## 当前与目标状态

| 能力 | 当前源码状态 | V2 目标 |
|---|---|---|
| 身份与 RBAC | 已有源码和迁移 | 保持并增加联盟链权限 |
| 请假审批 | 已有源码和状态机 | 增加专用证据事件 |
| Kafka Outbox/Inbox | 已有实现 | 新增链服务独立消费者组和 Inbox |
| 附件 SHA-256 | 已有 | 增加不可变附件证据快照契约 |
| 操作日志 | 已有 | 保持传统审计，不作为链事实源 |
| `blockchain-service` | 未实现 | 新增独立服务和数据库 |
| 智能合约 | 未实现 | 新增 `ApprovalEvidenceRegistry` |
| 联盟链网络 | 未部署 | 两组织、多节点实验拓扑 |
| 验真与故障实验 | 未实现 | 作为毕设验收重点 |

## 目标架构

```text
Browser
  │
  ▼
gateway-service
  ├── identity-service
  ├── approval-service ── Outbox ── Kafka ── blockchain-service ── 联盟链
  ├── office-service                           │
  └── audit-service                            └── redblack_blockchain

office-service ── 附件 SHA-256 / 不可变快照 ──┘
```

建议逻辑联盟组织：

- `OrgEnterprise`：企业组织，提交审批证据。
- `OrgAudit`：审计组织，参与共识和独立验证。

如果所有节点运行在同一台 VM，论文和文档必须称为“多组织仿真联盟链”；只有组织节点分别部署在不同主机时，才描述为跨主机实验网络。

## 技术基线

- Java 21（根 POM 强制约束）
- Spring Boot 3.5.x
- Maven Wrapper
- MySQL、Redis、Kafka
- Flyway
- Docker Compose / 虚拟机实验环境
- 阿里云 OSS 或兼容对象存储
- 联盟链候选：FISCO BCOS 3.x、PBFT 类共识、Solidity

FISCO BCOS 和 Java SDK 的确切版本必须先完成 Java 21 兼容性 POC，未验证前不得写成项目既成事实。

## 构建

准备 Java 21 后，在仓库根目录运行：

```powershell
./mvnw.cmd clean verify
```

该命令只验证当前源码能够完成的构建和测试；在区块链模块实现前，不代表联盟链功能通过。

## 配置与密钥

现有服务所需凭据以环境变量或只读 Secret 提供，例如：

- `JWT_PUBLIC_KEY_PATH`
- `JWT_PRIVATE_KEY_PATH`
- `INTERNAL_SERVICE_TOKEN_SECRET`
- 各服务独立数据库账号和密码
- OSS 访问凭据

区块链阶段还需要：

- 链网关/节点连接配置
- SDK 证书目录
- 组织交易账户私钥
- `redblack_blockchain` 独立数据库凭据
- 合约地址和版本清单

禁止将 JWT 私钥、服务密钥、数据库密码、OSS 密钥、链私钥或证书正文提交到 Git。

## 文档

- [产品需求文档](docs/产品需求文档.md)：产品范围、领域模型、联盟链能力和验收。
- [权限设计](docs/权限设计.md)：角色、权限、数据范围和服务责任。
- [接口文档](docs/接口文档.md)：HTTP、内部服务、事件、哈希和错误码契约。
- [OpenAPI](contracts/openapi/redblack-oa-v1.openapi.yaml)：当前 V1 机器可读接口快照，V2 开发时同步升级。
- `docs/archive/`：历史验收与设计材料，不作为当前实现状态证明。
- `evidence/`：历史命令输出和测试证据，只对对应提交与环境有效。

契约不一致时暂停实现，先更新和评审文档，再同步 OpenAPI、代码、迁移和测试。

## 毕设实施顺序

1. 完成 Java 21 + 联盟链 Java SDK 最小 POC。
2. 固化链版本、节点数、组织、共识和附件哈希根算法。
3. 定义 `EvidenceRequested` 事件和不可变附件快照。
4. 实现 `ApprovalEvidenceRegistry` 合约及合约测试。
5. 新增 `blockchain-service`、独立数据库、Inbox 和存证状态机。
6. 接入网关、RBAC、菜单、前端页面和 OpenAPI。
7. 完成重复事件、篡改、网络中断、回执未知和节点故障实验。
8. 记录性能分位数、成功率、资源配置和实验限制，形成论文证据。

## 完成口径

只有同时满足以下条件，才能声称联盟链部分完成：

- 合约和链服务源码存在并通过测试。
- 真实实验网络能够出块并返回交易回执。
- OA 正式动作能够异步形成链上证据。
- 交易可通过哈希和区块高度查询。
- 链下篡改能够被验真发现。
- 重复事件不会重复上链。
- 链停机不阻塞 OA，恢复后能够补偿。
- 权限、隐私字段和密钥边界通过验收。
- 部署、清理、复现和实验步骤有实际执行证据。

