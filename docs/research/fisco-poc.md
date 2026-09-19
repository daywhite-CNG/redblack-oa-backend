# P0：FISCO BCOS 3.x × Java 21 技术可行性验证

结论（2026-09-19）：**已在 Windows 11 的 Microsoft OpenJDK 21.0.9 上真实运行成功，并连续执行两次**。Java SDK 通过 TLS 连接本机 Ubuntu WSL 的四节点 FISCO BCOS 3.7.3，读取链高、部署 Solidity 合约、写入随机证据、查询并比对结果，取得部署与写入交易哈希及区块号。此结果只证明当前版本组合和本机实验环境的最小交互，不等于 OA 链服务或生产环境验收。

## 范围、版本和裁决

根 `pom.xml` 使用 Java 21、Spring Boot 3.5.16、Maven Enforcer `[21,22)`；当前根模块仅有 `redblack-common`、gateway、identity、approval、office、audit，尚无 `blockchain-service`。本 POC 位于 `tools/fisco-poc/`，有自己的 POM 和 `.mvn`，不加入根 `<modules>`、不继承 Spring Boot、不连接 Kafka/MySQL，也未修改 approval/identity/office/audit 业务代码。

| 组件 | 本次版本 / 环境 | 核验方式 |
|---|---|---|
| 操作系统 / 节点宿主 | Windows 11 amd64 / Ubuntu 24.04 WSL2，glibc 2.39 | 本机命令 |
| Java | Microsoft OpenJDK 21.0.9+10-LTS | `java -version` 与两次 POC 输出 |
| Maven | 3.9.16 | `mvn -version`、Enforcer |
| FISCO BCOS | Air 3.7.3，Git commit `5811f123b0a82928de8ec662e84763d67c16fb1e` | 官方二进制 `--version` 与 SDK 链兼容版本 |
| Java SDK | `org.fisco-bcos.java-sdk:fisco-bcos-java-sdk:3.8.0` | 独立 POM / 实际交互 |
| SDK JNI | `org.fisco-bcos:bcos-sdk-jni:3.7.0` | SDK 3.8.0 传递依赖 |
| Solidity 编译器 | solc-js `0.8.11+commit.d7f03943.Emscripten.clang` | `npx --yes solc@0.8.11 --version`，合约固定 `pragma 0.8.11` |
| 拓扑 | `chain0/group0`，四节点 PBFT、非国密 EVM，P2P 30300–30303、RPC 20200–20203 | 生成配置、四个进程、node0 日志 `connected count=3` |

[FISCO BCOS 官方仓库](https://github.com/FISCO-BCOS/FISCO-BCOS)把 3.7.3 列为稳定版本，3.17.0 是新特性版本。网络调查建议的 3.17.0 与 SDK 调查存在版本配对冲突：公开 Java SDK 最新 Maven 发布为 3.8.0，[GitHub 3.8.0 发布页](https://github.com/FISCO-BCOS/java-sdk/releases/tag/v3.8.0)标注为 Pre-release，未找到官方对“节点 3.17.0 + SDK 3.8.0 + Java 21”的明确保证。因此 P0 选 3.7.3 + 3.8.0，依靠实跑而非版本号推测兼容；[官方 3.10 组件表](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/introduction/change_log/3_10_0.html)亦将 Java SDK 3.8.0 列为推荐。官方 SDK CI 的 Windows 构建未直接覆盖 Java 21，所以本机完整交互才是本次结论的依据，而不是 `mvn compile`。

## 最小契约与结构

`EvidenceRegistry.sol` 只接受不透明的十六进制字符串 `evidenceId` 和 SHA-256 形式的 `evidenceHash`，禁止空值、禁止相同 ID 覆盖；`getEvidence(id)` 返回原哈希，不存在则报错。链上没有 OA 人名、请假原因、审批意见或附件明文。每次运行自动生成新 ID/哈希并部署新合约，无需人工改链上数据。此合约仅是 POC，不是目标业务合约，也不证明业务权限、状态机或多组织治理。

```text
tools/fisco-poc/
  pom.xml, .mvn/maven.config       独立 Java 21 构建
  contracts/EvidenceRegistry.sol  最小链上写读契约
  compiled/*.abi, *.bin           solc-js 0.8.11 编译产物
  prepare-chain.sh                Ubuntu WSL 首次构链/后续安全复用
  run-poc.ps1                     Windows Java 21 两次可重复运行入口
  config.toml.example             SDK TLS 配置示例
  runtime/, conf/, logs/          本地链数据、私钥、完整日志；均不入 Git
```

使用 [官方 Air 构链说明](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/quick_start/air_installation.html)、[Java SDK 配置说明](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/sdk/java_sdk/config.html)与[SDK 快速开始](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/sdk/java_sdk/quick_start.html)确认节点、证书和 API。SDK 相对 `certPath=conf` 按 POC 进程工作目录解析；脚本先切到该目录，并从节点生成目录复制 `ca.crt`、`sdk.crt`、`sdk.key` 到 Git 忽略的 `conf/`。私钥和节点数据不提交。

## 复现

先准备 Ubuntu 24.04 WSL2（官方节点二进制依赖 glibc）。在 PowerShell 中运行：

```powershell
wsl.exe -d Ubuntu-24.04 -u root -- bash '/mnt/d/WorkSpace/毕业设计/redblack-oa-backend/tools/fisco-poc/prepare-chain.sh'
Set-Location 'D:\WorkSpace\毕业设计\redblack-oa-backend\tools\fisco-poc'
npx.cmd --yes solc@0.8.11 --bin --abi -o compiled contracts/EvidenceRegistry.sol
./run-poc.ps1
./run-poc.ps1
```

需要可用的 `curl`、`openssl`、`bash`、Node/npm、Maven 3.9+ 和指定 Java 21。`prepare-chain.sh` 首次从官方 v3.7.3 release 下载构链脚本及二进制，只在 `runtime/nodes` 不存在时生成；若发现半成品则退出，不覆盖已有节点数据。上游 3.7.3 构链脚本的账户脚本 CDN 返回 403，脚本用固定虚拟管理员地址让其生成配置，并在**首次启动前**自动把四份生成的 genesis 改为 `is_auth_check=false`；这只是本机无权限实验链，不能用于生产。后续启动不会修改已有创世数据。已测试脚本在运行中的节点上安全复用。停链可在 WSL 中执行 `bash runtime/nodes/127.0.0.1/stop_all.sh`。

`run-poc.ps1` 显示并保留 Maven/Java 版本、完整 stdout/stderr 和失败堆栈，失败返回非零；日志保存在忽略的 `logs/poc-时间戳.log`。每次均检查部署/写回执 `status=0`、非空交易哈希/区块号、按哈希查回写回执、view 查询值相等、最终高度不低于写入区块。不会调用 OA 服务、Kafka、MySQL。

## 本机实际结果

| 检查 | 第一次（09:52:02） | 第二次（09:52:25） |
|---|---|---|
| 初始链高 | 0 | 2 |
| 部署地址 | `0x6849f21d1e455e9f0712b1e99fa4fcd23758e8f1` | `0xc8ead4b26b2c6ac14c9fd90d9684c9bc2cc40085` |
| 部署交易哈希 | `0x7676ef891a587947bb8aba011eb9e45262e95eec11004d53602ede334db3c196` | `0x8f7853e6564d846dacb1512fe91158c07edc322839e9e946bd0e1b3d3cc2831c` |
| 部署区块 | 1 | 3 |
| 写入交易哈希 | `0xab4ac7d1e6fa8b533539bc081940c906ad1adc704ed0528014187ddae830432d` | `0xe8231a43b4f3cd677362423961a1145b59e59b386e15e72a9ddcde4403109fd5` |
| 写入区块 | 2 | 4 |
| 查询 | 与本次 `EVIDENCE_HASH` 完全一致 | 与本次 `EVIDENCE_HASH` 完全一致 |
| 最终链高 / 结果 | 2 / PASS | 4 / PASS |

两次完整输出分别在 `tools/fisco-poc/logs/poc-20260919-095202.log` 和 `poc-20260919-095225.log`（本机忽略目录）；日志包含各自随机 ID、哈希、SDK/Java/Maven 版本和回执。node0 日志确认三个 P2P 对端连接；这是同机四节点，**不**是跨主机或真正两组织部署。

## 失败经过和边界

1. Docker Desktop 的本机守护进程未正常运行；docker-desktop WSL 的 Alpine/musl 官方节点二进制缺 `__cpu_indicator_init`、`__res_init`、`__cpu_model`，因此安装独立 Ubuntu 24.04 WSL2（glibc 2.39）。不能把这解释成 Java 21 失败。
2. 官方 3.7.3 `build_chain.sh` 的 Tassl/get_account CDN 请求返回 HTTP 403；非国密构链使用系统 OpenSSL，账户脚本按上述自动配置规避。第一次传 `-v 3.7.3` 少了标签 `v`，下载得到 9 字节非归档文件；纠正为 `-v v3.7.3` 后官方二进制 `--version` 成功。
3. 已启动节点后直接改变 genesis 的权限开关会报 `The Genesis Data is inconsistent with the initial Genesis Data`；仅清除了本次新建的四个节点 `data/` 并在首次启动前统一设置，随后成功。正式复现脚本没有此问题。
4. 根 `.mvn/maven.config` 的相对 settings 路径会影响子目录；独立 `.mvn/maven.config` 隔离后解决。SDK 的传递注解处理器在 Java 21 报 `jdk.compiler does not opens ... JavacProcessingEnvironment`；POC 不使用注解处理，设置 `maven-compiler-plugin <proc>none</proc>` 后编译成功。
5. 第一次 Java 调用误用了 `deployAndGetResponse(abi, signedTx)` 双参数重载，将 BIN 当成已签名交易，节点返回 `InvalidGroupId`。根据 SDK 实际方法签名改用 `deployAndGetResponse(abi, bin, List.of())`，连续两次完成全部链交互。上述失败的完整堆栈分别保留在忽略日志目录，不把失败误报为兼容结论。

已证实的是这台机器上 Java 21 + SDK 3.8.0 + 节点 3.7.3 的最小闭环。未验证跨主机节点、联盟组织权限、实际 OA 事件契约、Kafka/MySQL 衔接、重复写入边界的运行时回归或链故障恢复；它们不是 P0 完成标准。本阶段**没有开始实现 `blockchain-service`**。

## 调查分工和来源

- 网络子 Agent：只读调查官方 Air 四节点、端口、证书与 3.17.0 路线；主 Agent 因版本配对不明裁决用稳定 3.7.3，并实际构链。
- SDK 子 Agent：只读核查 3.8.0 Maven/JNI、Java 21 官方验证缺口、TLS 配置和回执 API；主 Agent以两次实跑确认。
- Solidity 子 Agent：只读设计最小证据映射、禁止覆盖、读写验收与敏感数据边界；主 Agent 创建独立合约与程序。

原始权威依据：[Air 构链](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/tutorial/air/build_chain.html)、[FISCO 3.7.3 release](https://github.com/FISCO-BCOS/FISCO-BCOS/releases/tag/v3.7.3)、[Java SDK 仓库](https://github.com/FISCO-BCOS/java-sdk)、[Java SDK 3.8.0 release](https://github.com/FISCO-BCOS/java-sdk/releases/tag/v3.8.0)、[Maven Central SDK 坐标](https://central.sonatype.com/artifact/org.fisco-bcos.java-sdk/fisco-bcos-java-sdk/3.8.0)、[SDK 配置](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/sdk/java_sdk/config.html)、[SDK 交易组装](https://fisco-bcos-doc.readthedocs.io/zh-cn/release-3/docs/sdk/java_sdk/assemble_transaction.html)。
