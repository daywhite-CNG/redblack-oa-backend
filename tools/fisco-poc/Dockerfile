# 使用官方 3.7.3 release 二进制；官方 Docker Hub 缺少同版本镜像。
FROM ubuntu:24.04
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates iproute2 libgomp1 libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY docker-runtime/image/fisco-bcos /usr/local/bin/fisco-bcos
RUN chmod 0755 /usr/local/bin/fisco-bcos
WORKDIR /data
ENTRYPOINT ["/usr/local/bin/fisco-bcos"]
