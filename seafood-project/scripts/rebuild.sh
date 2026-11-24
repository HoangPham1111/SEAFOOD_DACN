#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn clean package
mkdir -p dist
cp target/seafood-2.0.0.jar dist/
docker compose up -d --build app
