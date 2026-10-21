#!/usr/bin/env sh
set -eu

echo '[1/6] Clean ONLY lab5 test containers and its volume'
docker compose down -v
echo '[2/6] Build images'
docker compose build
echo '[3/6] Start facade, business, storage and H2'
docker compose up -d facade
echo '[4/6] Place order through external UDP -> Protobuf -> H2'
docker compose run --rm -e PERSISTENCE_PHASE=seed tests mvn -B -q -Dtest=DockerPersistenceTest test
echo '[5/6] Restart database AND storage container (volume persists)'
docker compose restart database storage
sleep 3
echo '[6/6] Verify same order survived restart'
docker compose run --rm -e PERSISTENCE_PHASE=verify tests mvn -B -q -Dtest=DockerPersistenceTest test
echo 'Docker persistence test PASSED.'
docker compose down
