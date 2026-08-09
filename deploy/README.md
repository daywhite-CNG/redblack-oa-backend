# 虚拟机部署与阶段验收

目标环境为 Ubuntu 虚拟机 `192.168.12.131`。MySQL、Redis 和 Kafka 仅加入 Compose 内部网络，只有 Nginx 的 `80` 端口对宿主机开放。

## 前置条件

- Docker Engine 与 Docker Compose 插件可用。
- `openssl`、`curl`、`python3` 可用。
- 项目已使用 Java 21 执行 `./mvnw -B -ntp clean verify` 并生成网关、身份服务 JAR。
- 已从 `deploy/KafkaRoundTripProbe.java` 编译生成 `deploy/KafkaRoundTripProbe.jar`；验收会复用现有 identity-service 镜像中的 Kafka 客户端依赖，不下载额外镜像。
- 已准备 `edenhill/kcat:1.7.1` 镜像，仅供韧性脚本确认指定 Outbox 事件被真实消费；验收过程不自动下载镜像。

Kafka 探针使用项目锁定的 Kafka 客户端编译：

```bash
./mvnw -q -pl identity-service dependency:copy-dependencies \
  -DincludeArtifactIds=kafka-clients -DoutputDirectory=../deploy/.probe-deps
rm -rf deploy/.probe-build
mkdir -p deploy/.probe-build
javac --release 21 -encoding UTF-8 -cp 'deploy/.probe-deps/*' \
  -d deploy/.probe-build deploy/KafkaRoundTripProbe.java
jar --create --file deploy/KafkaRoundTripProbe.jar -C deploy/.probe-build .
```

## 首次部署

```bash
cp deploy/.env.example deploy/.env
```

修改 `deploy/.env` 中的三个密码/密钥值。`INTERNAL_SERVICE_TOKEN_SECRET` 至少 32 字符，数据库普通用户密码与 root 密码必须不同。随后执行：

```bash
sudo ./deploy/prepare-secrets.sh
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

不要把 `deploy/.env` 或 `deploy/secrets/` 提交到 Git。

### 在既有 MySQL 卷上增加审批库

第三阶段升级既有部署时，在启动 `approval-service` 前执行一次：

```bash
./deploy/prepare-approval-database.sh
```

脚本创建独立的 `redblack_approval` 数据库和服务账号；若 `.env` 尚未配置审批库密码，脚本会生成随机密码并保持文件权限为 `600`。全新 MySQL 卷会自动执行 `deploy/mysql-init/01-create-service-databases.sh`。

## 验收

```bash
./deploy/verify-phase2.sh
python3 ./deploy/verify-identity-api.py http://127.0.0.1/api/v1
./deploy/verify-phase2-resilience.sh
python3 ./deploy/verify-phase3-api.py http://127.0.0.1/api/v1
./deploy/verify-phase3-infrastructure.sh
./deploy/verify-phase3-resilience.sh
```

脚本会依次检查六个容器、Flyway 与演示数据、Redis 往返、Kafka `acks=all` 写入及唯一消费组读取，以及经 Nginx 和网关完成登录、鉴权、员工越权拒绝和退出令牌失效。
Python 验收会真实调用身份服务归属的 35 个公开操作，并覆盖部门领导 SQL 范围、普通员工越权、用户与部门写操作对象隐藏、幂等缓存命中前重新鉴权和资源清理。
韧性验收会清空 Redis 并验证权限快照从 MySQL 回建；随后临时停止 Kafka，确认登录事实与 Outbox 保持成功，待 Kafka 恢复后事件变为 `SENT` 且可被真实消费。

若验收失败，先查看：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs --tail=200 identity-service gateway-service mysql redis kafka
```
