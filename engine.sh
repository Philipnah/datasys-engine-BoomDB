#!/usr/bin/env bash
# Runs the packaged engine: ./engine.sh -c "SELECT ..." or ./engine.sh -f script.sql
exec java $ENGINE_JAVA_OPTS -jar "$(dirname "$0")/target/BoomDB.jar" "$@"
