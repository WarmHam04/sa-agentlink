#!/usr/bin/env bash
set -euo pipefail

TOXIPROXY_API="${TOXIPROXY_API:-http://127.0.0.1:8474}"
ACTION="${1:-status}"

case "$ACTION" in
  init)
    curl --fail --silent --show-error -X DELETE \
      "$TOXIPROXY_API/proxies/agentlink-postgres" || true
    curl --fail --silent --show-error -X POST "$TOXIPROXY_API/proxies" \
      -H 'Content-Type: application/json' \
      -d '{"name":"agentlink-postgres","listen":"0.0.0.0:15432","upstream":"postgres:5432","enabled":true}'
    ;;
  latency)
    LATENCY_MS="${LATENCY_MS:-1000}"
    curl --fail --silent --show-error -X POST \
      "$TOXIPROXY_API/proxies/agentlink-postgres/toxics" \
      -H 'Content-Type: application/json' \
      -d "{\"name\":\"postgres-latency\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"latency\":$LATENCY_MS,\"jitter\":100}}"
    ;;
  cut)
    curl --fail --silent --show-error -X POST \
      "$TOXIPROXY_API/proxies/agentlink-postgres/toxics" \
      -H 'Content-Type: application/json' \
      -d '{"name":"postgres-timeout","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":0}}'
    ;;
  reset)
    curl --fail --silent --show-error -X DELETE \
      "$TOXIPROXY_API/proxies/agentlink-postgres/toxics/postgres-latency" || true
    curl --fail --silent --show-error -X DELETE \
      "$TOXIPROXY_API/proxies/agentlink-postgres/toxics/postgres-timeout" || true
    ;;
  status)
    curl --fail --silent --show-error "$TOXIPROXY_API/proxies/agentlink-postgres"
    ;;
  *)
    echo "usage: $0 {init|latency|cut|reset|status}" >&2
    exit 2
    ;;
esac
