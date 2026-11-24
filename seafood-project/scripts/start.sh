#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d --build
printf 'Mở http://localhost:8080 sau khi app khởi động.\n'
