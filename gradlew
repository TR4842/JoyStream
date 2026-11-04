#!/bin/sh
#
# Minimal Gradle wrapper launcher — the wrapper jar does the real work
# (downloading the distribution and forwarding all arguments).
#
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVACMD="java"
else
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
    exit 1
fi

if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: missing $CLASSPATH. Run 'gradle wrapper' to regenerate it." >&2
    exit 1
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
