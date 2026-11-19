@echo off
setlocal

if defined GRADLE_HOME goto from_home
where gradle.bat >nul 2>&1
if errorlevel 1 goto missing
gradle.bat %*

:from_home
if not exist "%GRADLE_HOME%\bin\gradle.bat" goto missing
"%GRADLE_HOME%\bin\gradle.bat" %*

:missing
>&2 echo Install Gradle 9.3.1 and set GRADLE_HOME or add its bin directory to PATH.
exit /b 127
