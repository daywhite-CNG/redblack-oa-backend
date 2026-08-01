# Red&Black OA Backend

Red&Black OA V1 后端雏形，严格遵循当前 PRD、权限设计、HTTP/OpenAPI 与事件契约。

## 模块

- `gateway-service`：统一入口、路由、身份前置检查和限流边界。
- `identity-service`：登录、用户、部门、角色、菜单与权限事实源。
- `approval-service`：请假申请、审批任务、审批记录与状态机。
- `office-service`：工作台读模型、公告、通知和 OSS 附件访问。
- `audit-service`：消费审计事件并提供操作日志查询。
- `redblack-common`：仅存放稳定的技术契约，不共享业务实体或 Mapper。

## 当前阶段

当前仅建立可编译的工程边界、依赖基线、启动类、健康检查配置、网关路由和安全默认值。所有尚未实现的受保护接口默认拒绝访问，不提供伪实现。

数据库迁移、JWT 双重校验、Redis 权限快照、Outbox/Inbox、Kafka 事件、OSS 文件流程和业务 API 将按模块逐步实现。

## 构建

需要 Java 21 和 Maven 3.9+：

```powershell
./mvnw.cmd clean verify
```

## 契约

浏览器 HTTP 契约快照位于 `contracts/openapi/redblack-oa-v1.openapi.yaml`。项目来源中的最新文档始终优先；契约不一致时暂停实现并先完成评审。

