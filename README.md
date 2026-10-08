# Red&Black OA Backend

面向小型企业的协同办公后端。以请假审批为业务载体，覆盖身份与权限、审批流转、工作台、公告通知、附件与操作审计，拆分为 6 个 Maven 模块。

## 项目目录

```text
redblack-oa-backend/
├── gateway-service/       # 统一入口、鉴权前置检查与路由
├── identity-service/      # 登录、用户、部门、角色、菜单和权限事实源
├── approval-service/      # 请假申请、审批任务、记录和业务状态机
├── office-service/        # 工作台、公告、通知和 OSS 附件
├── audit-service/         # 操作日志消费、存储和查询
├── redblack-common/       # 稳定技术契约，不共享业务实体
├── contracts/             # 当前 OpenAPI 机器契约
├── deploy/                # 本地/虚拟机部署与验收脚本
├── docs/                  # 契约索引与历史验收材料
└── evidence/              # 历史执行证据，不代替当前验证
```

## 已实现能力

### 身份与权限

- RS256 非对称 JWT：identity-service 以私钥签发，gateway-service 只用公钥校验，两侧不共享签名密钥。
- 网关统一鉴权前置检查，并签发内部服务令牌；服务间调用携带请求 ID 与超时控制。
- RBAC 事实源覆盖用户、部门、角色、菜单与权限标识，支持数据范围与对象级授权。
- 权限快照存放于 Redis；权限变更后按用户 evict 缓存，避免旧权限继续生效。

### 请假审批

- 草稿、提交、撤回、重新提交、同意、驳回、转交的完整状态机。
- 幂等记录以 `actor_id + http_method + request_path + idempotency_key` 为主键，重复提交不产生重复业务事实。
- 申请带乐观锁版本字段，审批并发冲突不会静默覆盖。

### 异步与一致性

- 写操作在同一本地事务内落业务表与 Outbox 表，事务提交后由 OutboxPublisher 投递 Kafka。
- 投递失败按退避策略重试；下游以 Inbox 表去重，在至少一次投递下保证幂等消费。
- Kafka 异步驱动工作台投影、通知与操作日志。

### 办公与文件

- 工作台聚合数据缓存于 Redis，公告与通知由事件异步更新。
- 附件保存于阿里云 OSS 私有 Bucket；下载先完整读取并校验长度、SHA-256 与 ETag，再重新打开对象流输出，避免返回不完整内容。
- 浏览器只调用受控文件接口，响应不暴露 Bucket、Object Key、ETag 与凭据。

## 契约

- [OpenAPI](contracts/openapi/redblack-oa-v1.openapi.yaml)：浏览器经 gateway-service 调用的 `/api/v1` 机器契约快照。
- 各服务的契约覆盖测试会解析该 OpenAPI，校验实现端点与契约一致。契约不一致时先更新契约，再同步代码、迁移和测试。

## 技术基线

- Java 21（根 POM 强制约束）
- Spring Boot 3.5.x、Spring Cloud Gateway
- Maven Wrapper 多模块构建
- MySQL（Flyway 迁移）、Redis、Kafka
- 阿里云 OSS 或兼容对象存储
- Docker Compose / 虚拟机实验环境

## 构建

准备 Java 21 后，在仓库根目录运行：

```powershell
./mvnw.cmd clean verify
```

## 配置与密钥

现有服务所需凭据以环境变量或只读 Secret 提供，例如：

- `JWT_PUBLIC_KEY_PATH`
- `JWT_PRIVATE_KEY_PATH`
- `INTERNAL_SERVICE_TOKEN_SECRET`
- 各服务独立数据库账号和密码
- OSS 访问凭据

禁止将 JWT 私钥、服务密钥、数据库密码或 OSS 密钥提交到 Git。

## 验收证据

- `evidence/phase2-vm-20260802/`：虚拟机真实环境执行记录，含环境信息、Git 状态、依赖摘要与 surefire 报告；当轮 7 模块、13 套件、19 用例，17 通过、0 失败、2 跳过。
- `docs/archive/acceptance-reports/`：阶段二至第四阶段后端验收报告。
- `docs/archive/design-concepts/`：界面设计截图。
- `docs/archive/test-evidence/`：阶段三补充测试脚本与执行材料。

以上材料只对对应提交与环境有效，不作为当前 HEAD 的实现状态证明。
