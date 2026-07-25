#!/usr/bin/env bash

set -euo pipefail

binary="${1:-./target/pocketbase-java}"
port="${PB_NATIVE_SMOKE_PORT:-8091}"
base_url="http://127.0.0.1:${port}"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketbase-native-smoke.XXXXXX")"
data_dir="${work_dir}/data"
log_file="${work_dir}/server.log"
server_pid=""

cleanup() {
  if [[ -n "${server_pid}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
    kill "${server_pid}" || true
    wait "${server_pid}" || true
  fi
  rm -rf "${work_dir}"
}
trap cleanup EXIT INT TERM

if [[ ! -x "${binary}" ]]; then
  echo "Native executable is missing or not executable: ${binary}" >&2
  exit 1
fi

start_server() {
  PB_STORAGE=sqlite "${binary}" --dir="${data_dir}" --port="${port}" >"${log_file}" 2>&1 &
  server_pid=$!
}

stop_server() {
  if [[ -n "${server_pid}" ]] && kill -0 "${server_pid}" 2>/dev/null; then
    kill "${server_pid}"
    wait "${server_pid}" || true
  fi
  server_pid=""
}

wait_for_health() {
  for _ in $(seq 1 30); do
    if curl --fail --silent --show-error "${base_url}/api/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  cat "${log_file}" >&2
  return 1
}

login() {
  local response
  response="$(curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --data '{"identity":"root@example.com","password":"secret123"}' \
    "${base_url}/api/collections/_superusers/auth-with-password")"
  printf '%s' "${response}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

assert_persisted_record() {
  local response
  response="$(curl --fail-with-body --silent --show-error \
    --header "Authorization: ${token}" \
    "${base_url}/api/collections/native_smoke_posts/records?filter=title%20%3D%20%27native%20sqlite%27")"
  printf '%s' "${response}" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
items = payload.get("items", [])
if len(items) != 1 or items[0].get("title") != "native sqlite":
    raise SystemExit("SQLite record did not persist across native restart")
'
}

start_server
wait_for_health

curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data '{"email":"root@example.com","password":"secret123"}' \
  "${base_url}/api/bootstrap/superuser" >/dev/null

token="$(login)"

curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --header "Authorization: ${token}" \
  --data '{"name":"native_smoke_posts","type":"base","listRule":"","viewRule":"","createRule":"","updateRule":"","deleteRule":"","fields":[{"name":"title","type":"text","required":true}]}' \
  "${base_url}/api/collections" >/dev/null

curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --header "Authorization: ${token}" \
  --data '{"title":"native sqlite"}' \
  "${base_url}/api/collections/native_smoke_posts/records" >/dev/null

stop_server
start_server
wait_for_health

token="$(login)"
assert_persisted_record

echo "Native SQLite smoke passed."
