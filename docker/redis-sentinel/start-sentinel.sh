#!/bin/sh
set -eu

# Sentinel은 승격된 primary와 자신의 ID를 이 파일에 기록한다.
if [ ! -f /data/sentinel.conf ]; then
    cp /config/sentinel.conf /data/sentinel.conf
    printf '\nsentinel announce-ip %s\n' "$SENTINEL_NODE_NAME" >> /data/sentinel.conf
fi

exec redis-server /data/sentinel.conf --sentinel
