@echo off
setlocal
REM Run via PowerShell script for accurate CSV collection on Windows.
where pwsh >nul 2>nul
if not errorlevel 1 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0benchmark.ps1"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0benchmark.ps1"
)
exit /b %errorlevel%
