#!/usr/bin/env bash
# Runs the packaged engine: ./engine.sh -c "SELECT ..." or ./engine.sh -f script.sql
exec java -jar "$(dirname "$0")/target/BoomDB.jar" "$@"
