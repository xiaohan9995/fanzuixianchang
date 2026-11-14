#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ -x "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java" ]; then
  JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
  export JAVA_HOME
fi
if command -v mvn >/dev/null 2>&1; then MAVEN_BIN=$(command -v mvn)
elif [ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]; then MAVEN_BIN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
else printf '%s\n' 'Maven 3.6.3+ is required. Install Maven or run using IntelliJ Maven support.' >&2; exit 1; fi
REPOSITORY="${MAVEN_LOCAL_REPOSITORY:-$SCRIPT_DIR/.m2-cache}"
cd "$SCRIPT_DIR"
exec "$MAVEN_BIN" -B "-Dmaven.repo.local=$REPOSITORY" spring-boot:run "$@"
