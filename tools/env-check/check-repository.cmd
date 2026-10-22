@echo off
rem Check the repository connection (docs/98). The result file is saved in the workspace folder (outside the repository).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-repository.ps1" -OutFile "%~dp0..\..\..\repo-result.txt"
pause
