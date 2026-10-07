@echo off
setlocal

echo [1/6] Removing old containers and test volume...
docker compose down -v
if errorlevel 1 exit /b 1

echo [2/6] Building Docker images...
docker compose build
if errorlevel 1 exit /b 1

echo [3/6] Starting UDP exchange server...
docker compose up -d exchange-server
if errorlevel 1 exit /b 1

echo [4/6] Writing state into H2 through UDP...
docker compose run --rm -e PERSISTENCE_PHASE=seed tests mvn -q -Dtest=DockerPersistenceTest test
if errorlevel 1 goto failed

echo [5/6] Restarting only the server container...
docker compose restart exchange-server
if errorlevel 1 goto failed

echo [6/6] Checking that the state survived the restart...
docker compose run --rm -e PERSISTENCE_PHASE=verify tests mvn -q -Dtest=DockerPersistenceTest test
if errorlevel 1 goto failed

echo.
echo Docker persistence test PASSED.
echo The named volume is intentionally kept after docker compose down.
docker compose down
exit /b 0

:failed
echo.
echo Docker persistence test FAILED.
docker compose down
exit /b 1
