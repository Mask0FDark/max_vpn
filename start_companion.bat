@echo off
cd /d "%~dp0"
set "NODE_OPTIONS=--max-old-space-size=1024"
python -m companion.server --open
pause
