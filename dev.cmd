@echo off
rem Flux developer commands: dev android ^| pc ^| build ^| run ^| test (see scripts\dev.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\dev.ps1" %*
