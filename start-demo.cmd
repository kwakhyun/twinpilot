@echo off
rem TwinPilot local demo launcher (double-click). Usage: start-demo.cmd [start^|stop^|reset^|logs]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-demo.ps1" %*
