@rem Minimal Gradle wrapper launcher for Windows.
@echo off
setlocal

set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
    set JAVACMD=java
)

if not exist "%CLASSPATH%" (
    echo ERROR: missing gradle\wrapper\gradle-wrapper.jar. Run "gradle wrapper" to regenerate it.
    exit /b 1
)

"%JAVACMD%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
