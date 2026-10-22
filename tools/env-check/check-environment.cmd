@echo off
rem Check the development environment (docs/98). The result file is saved in the workspace folder (outside the repository).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-environment.ps1" -OutFile "%~dp0..\..\..\env-result.txt"
pause
