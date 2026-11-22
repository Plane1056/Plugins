@echo off
call mvn --batch-mode --no-transfer-progress clean verify
if errorlevel 1 (pause & exit /b 1)
echo Built target\ApexGuns-1.0.0.jar
pause
