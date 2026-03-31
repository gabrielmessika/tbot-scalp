#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# T-Bot Scalp — run.sh
# Builds (optional), launches the bot, tees output to terminal + log file.
# Usage:
#   ./run.sh           — build JAR then run
#   ./run.sh --no-build — skip build, reuse existing JAR
# =============================================================================

JAVA_HOME=/usr/java/default21
PATH="$JAVA_HOME/bin:/usr/local/maven/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export JAVA_HOME PATH

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/tbot-scalp-1.0.0.jar"
LOG_DIR="$SCRIPT_DIR/logs"
TIMESTAMP="$(date +%Y-%m-%d_%H-%M-%S)"
LOG_FILE="$LOG_DIR/tbot-scalp_${TIMESTAMP}.log"

# Parse arguments
BUILD=true
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=false ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

# Ensure log directory exists
mkdir -p "$LOG_DIR"

# Build if requested
if [ "$BUILD" = true ]; then
  echo ">>> Building JAR..."
  mvn clean package -DskipTests -q
  echo ">>> Build complete."
fi

# Verify JAR exists
if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR — run without --no-build first." >&2
  exit 1
fi

echo ">>> Starting T-Bot Scalp"
echo "    JAR : $JAR"
echo "    Log : $LOG_FILE"
echo "    Time: $TIMESTAMP"
echo ""

# Run — tee stdout+stderr to terminal and log file simultaneously
exec java \
  -DLOG_FILE="$LOG_FILE" \
  -jar "$JAR" \
  2>&1 | tee "$LOG_FILE"
