#!/bin/sh
set -eu

# Sentinel의 CONFIG REWRITE 결과를 보존해 재시작 시 예전 역할로 되돌리지 않는다.
if [ ! -f /data/redis.conf ]; then
    cp /config/redis.conf /data/redis.conf
    printf '\nreplica-announce-ip %s\n' "$REDIS_NODE_NAME" >> /data/redis.conf

    if [ -n "${INITIAL_PRIMARY:-}" ]; then
        printf 'replicaof %s 6379\n' "$INITIAL_PRIMARY" >> /data/redis.conf
    fi
fi

exec redis-server /data/redis.conf
