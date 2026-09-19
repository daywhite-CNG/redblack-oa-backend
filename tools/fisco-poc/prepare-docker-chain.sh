#!/usr/bin/env bash
# 用官方 3.7.3 构链脚本生成专用 Docker 节点配置，并下载同版本官方二进制。
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
runtime="$root/docker-runtime"
nodes="$runtime/nodes"
image="$runtime/image"
ips=(172.29.44.10 172.29.44.11 172.29.44.12 172.29.44.13)

if [[ -e "$nodes" ]]; then
    for ip in "${ips[@]}"; do
        [[ -f "$nodes/$ip/node0/config.genesis" && -f "$nodes/$ip/sdk/sdk.key" ]] || {
            echo "Docker 节点目录不完整：$nodes/$ip；不会覆盖既有身份或链数据。" >&2
            exit 1
        }
    done
    [[ -x "$image/fisco-bcos" ]] || { echo '缺少固定版本节点二进制，拒绝覆盖已有网络。' >&2; exit 1; }
    "$image/fisco-bcos" --version
    echo "现有 Docker 链配置可复用：$nodes"
    exit 0
fi

mkdir -p "$runtime" "$image" "$HOME/.fisco"
if [[ ! -e "$HOME/.fisco/tassl-1.1.1b" ]]; then
    # 只创建非国密实验链；官方脚本的 Tassl CDN 当前返回 403。
    ln -s /usr/bin/openssl "$HOME/.fisco/tassl-1.1.1b"
fi
cd "$runtime"
curl -fL 'https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.7.3/build_chain.sh' -o build_chain.sh
bash build_chain.sh -D -v v3.7.3 \
    -a 0x0000000000000000000000000000000000000001 \
    -l '172.29.44.10:1,172.29.44.11:1,172.29.44.12:1,172.29.44.13:1' \
    -p 30300,20200 -o nodes

for ip in "${ips[@]}"; do
    node="$nodes/$ip/node0"
    # 固定虚拟管理员地址只用于绕过失效的 get_account.sh 下载；首次启动前关闭权限。
    sed -i 's/is_auth_check=true/is_auth_check=false/' "$node/config.genesis"
    grep -q 'is_auth_check=false' "$node/config.genesis"
    mkdir -p "$node/data" "$node/log"
    chmod 600 "$node/conf/node.pem" "$node/conf/ssl.key" "$nodes/$ip/sdk/sdk.key"
done

curl -fL 'https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.7.3/fisco-bcos-linux-x86_64.tar.gz' \
    -o "$image/fisco-bcos-linux-x86_64.tar.gz"
tar -tzf "$image/fisco-bcos-linux-x86_64.tar.gz" | grep -Fx 'fisco-bcos' > /dev/null
tar -xzf "$image/fisco-bcos-linux-x86_64.tar.gz" -C "$image" fisco-bcos
chmod 0755 "$image/fisco-bcos"
"$image/fisco-bcos" --version
sha256sum "$image/fisco-bcos" > "$image/fisco-bcos.sha256"
echo "Docker 链配置已生成：$nodes"
echo "SDK 证书目录：$nodes/${ips[0]}/sdk"
