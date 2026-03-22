@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set GRADLE_USER_HOME=%APP_HOME%.gradle-user-home

set DEFAULT_JVM_OPTS=

set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
  echo Missing gradle-wrapper.jar in %WRAPPER_JAR%
  echo Run Gradle wrapper task from Android Studio to generate it.
  exit /b 1
)

"%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
