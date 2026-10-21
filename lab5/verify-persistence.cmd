@echo off
setlocal

echo [1/6] Clean test data...
docker compose down -v
if errorlevel 1 goto failed

echo [2/6] Build images...
docker compose build
if errorlevel 1 goto failed

echo [3/6] Start system...
docker compose up -d facade
if errorlevel 1 goto failed

echo [4/6] Seed order through UDP and Protobuf...
docker compose run --rm -e PERSISTENCE_PHASE=seed tests mvn -B -q -Dtest=DockerPersistenceTest test
if errorlevel 1 goto failed

echo [5/6] Restart H2 and storage...
docker compose restart database storage
if errorlevel 1 goto failed
timeout /t 3 /nobreak >nul

echo [6/6] Verify restored order...
docker compose run --rm -e PERSISTENCE_PHASE=verify tests mvn -B -q -Dtest=DockerPersistenceTest test
if errorlevel 1 goto failed

echo Docker persistence test PASSED.
docker compose down
exit /b 0

:failed
echo Docker persistence test FAILED.
docker compose down
exit /b 1
