#!/bin/sh
set -e

SENTINEL_PORT=${SENTINEL_PORT:-26379}
MASTER_HOST=${MASTER_HOST:-redis-master}
MASTER_PORT=${MASTER_PORT:-6379}
QUORUM=${QUORUM:-2}

CONF_FILE="/tmp/sentinel.conf"

cat <<EOF > "$CONF_FILE"
port $SENTINEL_PORT
dir /tmp
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel monitor mymaster $MASTER_HOST $MASTER_PORT $QUORUM
sentinel down-after-milliseconds mymaster 3000
sentinel failover-timeout mymaster 6000
sentinel parallel-syncs mymaster 1
EOF

chmod 777 "$CONF_FILE"

echo "Starting Redis Sentinel on port $SENTINEL_PORT monitoring $MASTER_HOST:$MASTER_PORT (quorum $QUORUM)..."
exec redis-server "$CONF_FILE" --sentinel
