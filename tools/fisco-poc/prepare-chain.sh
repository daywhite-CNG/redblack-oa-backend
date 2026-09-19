#!/usr/bin/env bash
# 在 Ubuntu WSL 中准备独立的四节点 FISCO BCOS 3.7.3 实验链。
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
runtime="$root/runtime"
nodes="$runtime/nodes/127.0.0.1"
mkdir -p "$runtime" "$HOME/.fisco"
if [[ ! -e "$HOME/.fisco/tassl-1.1.1b" ]]; then
    # 本 POC 是非国密 ECDSA，系统 OpenSSL 满足脚本所需的证书命令。
    ln -s /usr/bin/openssl "$HOME/.fisco/tassl-1.1.1b"
fi
if [[ ! -f "$nodes/sdk/sdk.key" ]]; then
    if [[ -e "$runtime/nodes" ]]; then
        echo '发现未完成的 runtime/nodes；请先确认并自行处理，不自动覆盖。' >&2
        exit 1
    fi
    cd "$runtime"
    curl -fL 'https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.7.3/build_chain.sh' -o build_chain.sh
    # 上游 3.7.3 的 get_account.sh CDN 当前返回 403；固定虚拟地址只用于生成配置，
    # 随即在首次启动前关闭本地 POC 的链上权限开关，不需要管理员私钥。
    bash build_chain.sh -v v3.7.3 -a 0x0000000000000000000000000000000000000001 \
        -l 127.0.0.1:4 -p 30300,20200 -o nodes
    for node in "$nodes"/node{0..3}; do
        sed -i 's/is_auth_check=true/is_auth_check=false/' "$node/config.genesis"
    done
fi
cd "$runtime"
bash "$nodes/start_all.sh"
"$nodes/fisco-bcos" --version
echo 'SDK_CERT_DIR='"$nodes/sdk"
