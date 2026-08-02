# Red&Black OA Backend

Red&Black OA V1 后端，严格遵循当前 PRD、权限设计、HTTP/OpenAPI 与事件契约。

## 模块

- `gateway-service`：统一入口、路由、身份前置检查和限流边界。
- `identity-service`：登录、用户、部门、角色、菜单与权限事实源。
- `approval-service`：请假申请、审批任务、审批记录与状态机。
- `office-service`：工作台读模型、公告、通知和 OSS 附件访问。
- `audit-service`：消费审计事件并提供操作日志查询。
- `redblack-common`：仅存放稳定的技术契约，不共享业务实体或 Mapper。

## 当前阶段

第二阶段“身份、组织与 RBAC 闭环”已进入本地实现：

- `identity-service` 提供 OpenAPI 约定的 35 个认证、账号、用户、部门、角色和菜单操作，以及 3 个内部身份查询。
- MySQL Flyway 迁移包含身份/RBAC、演示数据、Outbox 和幂等记录；业务时间按 UTC 处理。
- 用户令牌使用 RS256，内部服务令牌使用最长 5 分钟的 HS256；私钥和共享密钥不进入仓库。
- Redis 权限快照默认 5 分钟，身份或授权变化在事务提交后失效；Redis 未命中时回源 MySQL。
- 身份事实与 Outbox 在同一事务提交，发布失败按有上限的指数退避重试。
- 网关移除外部伪造身份头，验证用户令牌、注销标记和 `authVersion`，权限缓存未命中时回源身份服务。

审批、办公、审计服务仍保持拒绝未实现接口的安全默认值。MySQL 8.4、Redis、Kafka 的 Testcontainers 验收需要本机 Docker 或后续集成环境；编译和单元/契约测试通过不等同于虚拟机最终验收。

## 构建

需要 Java 21 和 Maven 3.9+：

```powershell
./mvnw.cmd clean verify
```

运行网关和身份服务还必须通过 Secret/环境提供：

- `JWT_PUBLIC_KEY_PATH`：两个服务读取的 X.509 RSA 公钥路径。
- `JWT_PRIVATE_KEY_PATH`：仅身份服务读取的 PKCS#8 RSA 私钥路径。
- `INTERNAL_SERVICE_TOKEN_SECRET`：至少 32 字符的内部服务令牌密钥。
- `IDENTITY_DB_USERNAME`、`IDENTITY_DB_PASSWORD`：身份库凭据。

默认 JWT 发行者为 `https://identity.redblack.local`，访问令牌受众为 `redblack-oa`，有效期固定为 8 小时。

## 契约

浏览器 HTTP 契约快照位于 `contracts/openapi/redblack-oa-v1.openapi.yaml`。项目来源中的最新文档始终优先；契约不一致时暂停实现并先完成评审。

