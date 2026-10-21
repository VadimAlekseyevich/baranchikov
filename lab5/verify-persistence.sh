#!/usr/bin/env sh
set -eu

echo "[1/6] Removing old containers and test volume..."
docker compose down -v

echo "[2/6] Building Docker images..."
docker compose build

echo "[3/6] Starting UDP exchange server..."
docker compose up -d exchange-server

echo "[4/6] Writing state into H2 through UDP..."
docker compose run --rm -e PERSISTENCE_PHASE=seed tests \
  mvn -q -Dtest=DockerPersistenceTest test

echo "[5/6] Restarting only the server container..."
docker compose restart exchange-server

echo "[6/6] Checking that the state survived the restart..."
docker compose run --rm -e PERSISTENCE_PHASE=verify tests \
  mvn -q -Dtest=DockerPersistenceTest test

echo "Docker persistence test PASSED."
echo "The named volume is intentionally kept after docker compose down."
docker compose down
