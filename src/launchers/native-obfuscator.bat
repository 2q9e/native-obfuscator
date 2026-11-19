@echo off
setlocal

set "java_command=java.exe"
if defined JAVA_HOME set "java_command=%JAVA_HOME%\bin\java.exe"
if defined JAVA_OPTS set "JDK_JAVA_OPTIONS=%JDK_JAVA_OPTIONS% %JAVA_OPTS%"
if defined NATIVE_OBFUSCATOR_OPTS set "JDK_JAVA_OPTIONS=%JDK_JAVA_OPTIONS% %NATIVE_OBFUSCATOR_OPTS%"
"%java_command%" -cp "%~dp0..\lib\*" dev.nativeobfuscator.Main %*
exit /b %errorlevel%
