#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(dirname "$SCRIPT_DIR")
if [ -n "${JAVA_HOME:-}" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; JAVAC_BIN="$JAVA_HOME/bin/javac";
elif [ -x "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac" ]; then JAVA_BIN="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java"; JAVAC_BIN="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac";
else JAVA_BIN=java; JAVAC_BIN=javac; fi
OUT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/fanzuixianchang-core.XXXXXX")
trap 'rm -rf "$OUT_DIR"' EXIT INT TERM
"$JAVAC_BIN" --release 21 -d "$OUT_DIR" "$PROJECT_DIR"/src/main/java/com/fanzuixianchang/core/*.java "$PROJECT_DIR"/src/test/java/com/fanzuixianchang/core/*Checks.java
"$JAVA_BIN" -cp "$OUT_DIR:$PROJECT_DIR/src/main/resources" com.fanzuixianchang.core.CoreChecks
"$JAVA_BIN" -cp "$OUT_DIR:$PROJECT_DIR/src/main/resources" com.fanzuixianchang.core.PolicyChecks

"$JAVA_BIN" -cp "$OUT_DIR:$PROJECT_DIR/src/main/resources" com.fanzuixianchang.core.NutritionChecks

"$JAVA_BIN" -cp "$OUT_DIR:$PROJECT_DIR/src/main/resources" com.fanzuixianchang.core.ProfessionalChecks
"$JAVA_BIN" -cp "$OUT_DIR:$PROJECT_DIR/src/main/resources" com.fanzuixianchang.core.IdentificationChecks
