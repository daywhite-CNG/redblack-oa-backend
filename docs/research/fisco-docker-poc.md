# P0.5：FISCO BCOS 3.7.3 Docker 四节点实测

状态（2026-09-19）：**A2 路线验收通过**。Ubuntu 24.04 WSL2 内的 Docker 四节点、Java 21 POC 两次、重启保留、fresh reset 后重跑均已实际完成。本结论只针对本地 WSL2 Docker 开发环境，不代表 Linux 云服务器已经部署。P0 原有 Windows Java 21.0.9／WSL 进程链结论与本次 Docker 结果分开，不复用旧交易数据。

## 版本与边界

| 项目 | 本次实测版本 |
| --- | --- |
| FISCO BCOS 节点 | 3.7.3，官方 release 二进制 |
| Java SDK | 3.8.0 |
| Solidity 编译结果 | 0.8.11，沿用 P0 ABI/BIN |
| WSL Java | Ubuntu OpenJDK 21.0.12（`openjdk version "21.0.12" 2026-07-21`） |
| Maven | 3.9.16（`/mnt/d/Dev/Maven/bin/mvn`） |
| Docker Engine / Compose | 29.1.3 / 2.40.3 |
| 宿主 | Ubuntu-24.04 on WSL2，NAT 模式 |

WSL 的 21.0.12 只用于 A2 运行，不修改 P0 锁定的 Windows Java 21.0.9、节点 3.7.3、SDK 3.8.0、Solidity 0.8.11。未修改 EvidenceRegistry.sol、ABI/BIN、P1 Evidence v1、OA 业务代码或根 POM；未接 Kafka/MySQL。Windows 因 WSL NAT 不能连通 WSL 的 `127.0.0.1:20200`，因此本次 Java 进程与 Docker Engine 同在 WSL 内，RPC 仍仅绑定回环地址；没有写 `.wslconfig`，没有切换 mirrored，也没有对外开放 RPC。

官方 [Docker 构链文档](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/tutorial/docker.html)支持 `build_chain.sh -D`，但 3.7.3 的官方镜像标签不可用。本项目用官方 3.7.3 [release](https://github.com/FISCO-BCOS/FISCO-BCOS/releases/tag/v3.7.3) 的构链脚本与节点二进制，使用 Ubuntu 24.04（非 Alpine）薄封装。四节点在 Compose bridge `172.29.44.0/24` 上使用 `.10`～`.13`；仅 node0 映射宿主 `127.0.0.1:20200`，不发布 P2P 30300。配置、证书只读挂载，数据和日志可写挂载在镜像外的 `docker-runtime/`；`conf/`、`runtime/`、`docker-runtime/`、`target/`、`logs/` 由 `.gitignore` 排除。证书与私钥的宿主备份需要按敏感数据保护。

## 可复现启动与 A2 POC

以下 shell 命令在 Ubuntu-24.04 WSL 内、仓库 `tools/fisco-poc` 目录执行；PowerShell 可用 `wsl.exe -d Ubuntu-24.04 --cd 'D:\WorkSpace\毕业设计\redblack-oa-backend\tools\fisco-poc' -- <命令>` 调用。P0 的 `run-poc.ps1` 默认行为保持不变。

```bash
apt-get install -y openjdk-21-jdk
java -version
bash prepare-docker-chain.sh
docker compose -p redblack-fisco-poc -f compose.docker.yaml config --quiet
docker compose -p redblack-fisco-poc -f compose.docker.yaml up -d --no-build
docker compose -p redblack-fisco-poc -f compose.docker.yaml ps
```

首次部署时先以现有 Dockerfile 构建固定镜像 `redblack/fisco-bcos:3.7.3-poc`。四节点同时 `up --build` 曾因并行构建同一镜像标签而报 `image already exists`；单次 `docker build -t redblack/fisco-bcos:3.7.3-poc -f Dockerfile .` 后再 `up -d --no-build` 成功，未修改 Dockerfile。构链脚本不会覆盖完整的既有网络；它会生成新的私钥与证书。每次 fresh reset 后必须将新 `docker-runtime/nodes/172.29.44.10/sdk/` 的 `ca.crt`、`sdk.crt`、`sdk.key` 复制到被忽略的 `conf/`，并检查三文件 SHA-256 一致；既有 `config.toml` 使用 `certPath = "conf"` 和 `peers = ["127.0.0.1:20200"]`。不需修改 Windows 入口。

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /mnt/d/Dev/Maven/bin/mvn -B -q compile exec:java "-Dexec.args=/mnt/d/WorkSpace/毕业设计/redblack-oa-backend/tools/fisco-poc/config.toml"
```

这条命令在 `tools/fisco-poc` 目录执行，重复运行即可产生新的证据 ID 与交易。Java POC 先获取链高，再部署合约、写 evidence、按交易哈希查回执、按合约方法查询 evidence，最后比较链高和查询值。两次运行均退出码 0、`RESULT=PASS`。

## 五项实测验收

1. **四容器健康与 P2P。** `docker compose -p redblack-fisco-poc -f compose.docker.yaml ps` 显示 node0～node3 均为 `Up ... (healthy)`，node0 唯一宿主映射为 `127.0.0.1:20200->20200/tcp`。在后端仓库运行 `rg --no-ignore -n -m 1 'heartBeat,connected count=3' tools/fisco-poc/docker-runtime/nodes`，逐一核对 `.10`、`.11`、`.12`、`.13` 的 `node0/log`；四个节点均出现 `heartBeat,connected count=3`。fresh reset 后四份日志于 2026-09-19 15:30:06～15:30:16 再次出现该值。
2. **第一次完整 POC。** `INITIAL_BLOCK_NUMBER=0`；部署哈希 `0xf169e3f7847a53298ea7eb6b18ec81cc31d42d70fcf8ef246ff91e313daed4e9`，部署区块 1；写入哈希 `0xedefcc13942c6013f0bf060c5af4276d0d693210aa9e0fb056f1c6208e7fd53b`，写入区块 2；`QUERIED_EVIDENCE=EVIDENCE_HASH=0xc7db77e17e127b0b89020a9d391352a4b0454b7065373eeff09613601b08625d`；`FINAL_BLOCK_NUMBER=2`，`RESULT=PASS`。
3. **第二次从既有链高追加。** `INITIAL_BLOCK_NUMBER=2`；部署哈希 `0xe0a4dfaadf8b055ae51d9430645eca06cc91b787ad5776bf67dcf2fdf08ba42d`，部署区块 3；写入哈希 `0xe9c796f583036bf3aa060dc2be81a72196f3a8d93bbfc4cd0cd0484d1bb4636b`，写入区块 4；`QUERIED_EVIDENCE=EVIDENCE_HASH=0x38a512c3b8d9fe1b7740ed80e5d988e6215932a49efd99796faa639214398cb7`；`FINAL_BLOCK_NUMBER=4`，`RESULT=PASS`。两次的证据和交易各不相同。
4. **重启保留。** `docker compose -p redblack-fisco-poc -f compose.docker.yaml restart` 后，`ps` 再次显示四节点 `healthy`。重启前链高 4；重启后的 POC 起始链高仍为 4，新部署／写入区块 5／6，写入哈希 `0x4403b9f1feed27ae86b08741d2558d439dff2d11190f47499c722fb3c848383d`，查询一致、`RESULT=PASS`。这验证账本 bind mount 保留；未单独按旧合约查询重启前的证据。
5. **fresh reset。** 先 `docker compose -p redblack-fisco-poc -f compose.docker.yaml down` 并确认 `ps` 为空；两仓库完整 tar 已保存到仓库外 `D:\WorkSpace\_backup\20260919-2323\`。在 PowerShell 用 `Resolve-Path` 严格比对确切的 `tools\fisco-poc\docker-runtime`，再 `Remove-Item -LiteralPath $target -Recurse -Force`；输出 `FRESH_RESET_DIRECTORY_REMOVED=True`。重新 `bash prepare-docker-chain.sh` 输出 `FISCO BCOS Version : 3.7.3` 和四节点生成成功；复制新 SDK 证书并校验三份 SHA-256 均匹配；`docker compose ... up -d --no-build` 后四节点 `healthy`、四份 P2P 日志均为三对端。重跑 POC 的 `INITIAL_BLOCK_NUMBER=0`，部署哈希 `0x243b0d2ea3cc1d6105ef8eb71ffb99688aaa5560b58a0f4e2f8901531b063f30`／区块 1，写入哈希 `0x9e05e85656732b055488d4ba0407ad8dd7f5daccfa20dd04ceb2acd35b0bf75a`／区块 2，`QUERIED_EVIDENCE=EVIDENCE_HASH=0x43d66ca3b392a51de4de1fa837c53a3194f5ecee4f3b88440beb7ba0de3f3b6e`，`RESULT=PASS`。旧链已由 tar 备份，当前运行的是新链。

## 停止、恢复与重置

- 常规停止：`docker compose -p redblack-fisco-poc -f compose.docker.yaml stop`；恢复：`docker compose -p redblack-fisco-poc -f compose.docker.yaml up -d --no-build`。`down` 删除容器和网络，但不会删除显式 bind mount 的账本目录；不要把 `down -v` 当作保留数据的停机方式。[Compose down 语义](https://docs.docker.com/reference/cli/docker/compose/down/)。
- 故障恢复：先检查 `docker compose ... ps`、`docker compose ... logs nodeN`、`docker-runtime/nodes/<IP>/node0/log/`；只有确认节点证书和账本仍在，才对指定节点 `restart nodeN`。不要对既有链重新运行会换身份的构链步骤。
- 全新重置：备份含私钥的 `docker-runtime/`，确认四容器退出，再精确校验并删除该目录；随后运行 `prepare-docker-chain.sh`、复制新 SDK 证书、`up -d --no-build`、检查 P2P 并运行 POC。不可删除 P0 的 `runtime/`；本次也未删除 P0 的 `logs/`。旧交易哈希不属于新链，不应用于新链验收。生产环境必须另行设计权限、密钥管理、备份和网络隔离；本 POC 的 `is_auth_check=false` 仅适用于本地实验。

## 失败与限制记录

- Windows `run-docker-poc.ps1` 曾因 WSL NAT 回环端口不互通，报 `BcosSDKException get Client failed`／`syncConnectToEndpoints`；Windows `Test-NetConnection 127.0.0.1 -Port 20200` 为 `False`。没有通过改 WSL 网络或开放 RPC 绕过；A2 把 Java 移入 WSL。
- 首次从 PowerShell 传递 Maven `-Dexec.args` 时发生引号拆分，Maven 报 `Unknown lifecycle phase ".args=..."`；改用 `wsl ... bash -lc` 将完整参数传给 Maven 后成功，未进入业务 POC 前该失败已结束。
- 第一次 fresh reset 的 WSL 命令在 `realpath` 校验处因引号拆分退出，`RESET_TARGET` 为空，未删除文件；随后使用同一 PowerShell 会话的 `Resolve-Path` 与 `Remove-Item -LiteralPath` 完成限定目录重置。
- Maven POC 每次以退出码 0 和 `RESULT=PASS` 完成，但 JVM 退出阶段打印 `libproviders.so: cannot open shared object file`；SLF4J 也提示未绑定 logger。这是已观察到的 SDK/JNI 退出提示，不影响已验证的部署、回执、查询和链高；其根因未在 P0.5 内进一步调查。
- 本次证据是本地 Docker 实跑，不是 Linux 云服务器验收；四节点同机，不证明跨主机容灾。镜像不含密钥，但仓库外 tar 快照包含运行时私钥，应限制访问并单独保管。
