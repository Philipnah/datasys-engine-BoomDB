#!/usr/bin/env bash
# Runs the packaged engine: ./engine "SELECT ..." or ./engine -f script.sql
exec java $ENGINE_JAVA_OPTS -jar "$(dirname "$0")/target/BoomDB.jar" "$@"