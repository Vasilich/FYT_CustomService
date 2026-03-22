#!/usr/bin/env sh

APP_HOME=$(cd "${0%/*}" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

GRADLE_USER_HOME="$APP_HOME/.gradle-user-home"
export GRADLE_USER_HOME

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Missing gradle-wrapper.jar in $WRAPPER_JAR"
  echo "Run Gradle wrapper task from Android Studio to generate it."
  exit 1
fi

exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
