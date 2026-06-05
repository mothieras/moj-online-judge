#!/usr/bin/env bash
#
# smoke-test.sh — End-to-end smoke test for the MOJ judge system
#
# This script verifies the core judge pipeline:
#   1. Login as a test user (session-based auth)
#   2. Submit a simple Java program as a solution
#   3. Poll the submission list until judging completes or times out
#   4. Assert the final status is a terminal state (SUCCEED or FAILED)
#
# Prerequisites:
#   - The MOJ backend must be running on http://localhost:8121
#   - curl and jq must be installed
#   - A user "testuser" with password "12345678" must exist
#   - A question with id=1 must exist in the database
#
# Exit codes:
#   0 - Smoke test passed (submission reached a terminal state)
#   1 - Smoke test failed (prerequisite missing, login failure, timeout, or unexpected error)

set -euo pipefail

# ─── helper functions ─────────────────────────────────────────────────────────

# Print a timestamped message to stderr (progress messages stay separate from data streams)
log() {
  echo "[$(date '+%H:%M:%S')] $*" >&2
}

# Print an error message and exit with code 1
die() {
  log "FATAL: $*"
  exit 1
}

# Extract a value from JSON using jq.  If jq fails or the path is null/empty, print
# the default value (second argument) instead of erroring out.
jq_val() {
  # jq -r for raw string;  if parsing fails return the default
  jq -r "$1" 2>/dev/null || { echo "${2:-}"; return 0; }
}

# ─── prerequisites ────────────────────────────────────────────────────────────

log "=== Checking prerequisites ==="

command -v curl >/dev/null 2>&1 || die "curl is not installed. Please install curl."
command -v jq   >/dev/null 2>&1 || die "jq is not installed. Please install jq."
log "curl:  $(command -v curl)"
log "jq:    $(command -v jq)"

# ─── configuration ────────────────────────────────────────────────────────────

BASE_URL="${MOJ_BASE_URL:-http://localhost:8121/api}"
POLL_TIMEOUT=60    # seconds
POLL_INTERVAL=2     # seconds

# Test credentials — must match an existing user in the database
USER_ACCOUNT="testuser"
USER_PASSWORD="12345678"

# Question to submit against (must exist)
QUESTION_ID=1
LANGUAGE="java"

# The Java program to submit (reads two command-line args and prints their sum)
# Base64-encode to avoid shell escaping nightmares with special characters
JAVA_CODE=$'public class Main {\n    public static void main(String[] args) {\n        Integer a = Integer.parseInt(args[0]);\n        Integer b = Integer.parseInt(args[1]);\n        System.out.println(a + b);\n    }\n}'

# Temporary cookie jar (cleaned up on exit)
COOKIE_JAR=$(mktemp /tmp/smoke-test-cookies.XXXXXX)
# Temporary file for response bodies (cleaned up on exit)
RESP_FILE=$(mktemp /tmp/smoke-test-response.XXXXXX)

# Clean up on any exit
cleanup() {
  rm -f "$COOKIE_JAR" "$RESP_FILE"
  log "Cleaned up temporary files."
}
trap cleanup EXIT

log "BASE_URL:   $BASE_URL"
log "Poll timeout: ${POLL_TIMEOUT}s, interval: ${POLL_INTERVAL}s"

# ─── step 1: login ──────────────────────────────────────────────────────────

log ""
log "=== Step 1: Login as '$USER_ACCOUNT' ==="

HTTP_CODE=$(curl -s -w '%{http_code}' -o "$RESP_FILE" \
  -c "$COOKIE_JAR" \
  -X POST "${BASE_URL}/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"userAccount\":\"${USER_ACCOUNT}\",\"userPassword\":\"${USER_PASSWORD}\"}")

RESP_BODY=$(cat "$RESP_FILE")

if [ "$HTTP_CODE" -ne 200 ]; then
  log "Login returned HTTP $HTTP_CODE"
  log "Response body: $RESP_BODY"
  die "Login failed (HTTP $HTTP_CODE). Is the server running and is the test user seeded?"
fi

# Check the BaseResponse wrapper's code field (0 = success)
RESP_CODE=$(echo "$RESP_BODY" | jq_val '.code')
if [ "$RESP_CODE" != "0" ]; then
  RESP_MSG=$(echo "$RESP_BODY" | jq_val '.message' 'unknown')
  log "Login API returned error code=$RESP_CODE, message=$RESP_MSG"
  die "Login API call was not successful."
fi

# Verify we got a session cookie (JSESSIONID for Spring Boot / Tomcat)
if ! grep -q 'JSESSIONID' "$COOKIE_JAR" 2>/dev/null; then
  log "Warning: No JSESSIONID cookie found. Continuing anyway..."
else
  log "Session cookie received."
fi

log "Login successful for user '$USER_ACCOUNT'."

# ─── step 2: submit code ────────────────────────────────────────────────────

log ""
log "=== Step 2: Submit Java solution (questionId=$QUESTION_ID) ==="

# Escape the code for JSON embedding using jq itself (handles newlines, quotes, etc.)
JSON_PAYLOAD=$(jq -n \
  --arg lang "$LANGUAGE" \
  --arg code "$JAVA_CODE" \
  --argjson qid "$QUESTION_ID" \
  '{language: $lang, code: $code, questionId: $qid}')

HTTP_CODE=$(curl -s -w '%{http_code}' -o "$RESP_FILE" \
  -b "$COOKIE_JAR" \
  -X POST "${BASE_URL}/question/question_submit/do" \
  -H 'Content-Type: application/json' \
  -d "$JSON_PAYLOAD")

RESP_BODY=$(cat "$RESP_FILE")

if [ "$HTTP_CODE" -ne 200 ]; then
  log "Submit returned HTTP $HTTP_CODE"
  log "Response body: $RESP_BODY"
  die "Code submission failed (HTTP $HTTP_CODE)."
fi

RESP_CODE=$(echo "$RESP_BODY" | jq_val '.code')
if [ "$RESP_CODE" != "0" ]; then
  RESP_MSG=$(echo "$RESP_BODY" | jq_val '.message' 'unknown')
  log "Submit API returned error code=$RESP_CODE, message=$RESP_MSG"
  die "Code submission API call was not successful."
fi

SUBMIT_ID=$(echo "$RESP_BODY" | jq_val '.data')

# Validate submission ID is a positive integer
if ! [[ "$SUBMIT_ID" =~ ^[0-9]+$ ]] || [ "$SUBMIT_ID" -le 0 ]; then
  log "Unexpected submission ID: '$SUBMIT_ID'"
  log "Full response: $RESP_BODY"
  die "Failed to extract a valid submission ID from the response."
fi

log "Code submitted successfully. Submission ID: $SUBMIT_ID"

# ─── step 3: poll for result ─────────────────────────────────────────────────

log ""
log "=== Step 3: Poll for judging result (timeout ${POLL_TIMEOUT}s) ==="

# Status constants (from QuestionSubmitVO)
# 0 = WAITING (pending)
# 1 = RUNNING  (judging in progress)
# 2 = SUCCEED  (accepted)
# 3 = FAILED   (wrong answer, TLE, etc.)
STATUS_WAITING=0
STATUS_RUNNING=1
STATUS_SUCCEED=2
STATUS_FAILED=3

RESULT_STATUS=""
RESULT_JUDGEINFO=""
RESULT_ELAPSED=0
FOUND=false

START_TIME=$(date +%s)

while [ "$RESULT_ELAPSED" -lt "$POLL_TIMEOUT" ]; do
  sleep "$POLL_INTERVAL"
  CURRENT_TIME=$(date +%s)
  RESULT_ELAPSED=$((CURRENT_TIME - START_TIME))

  # Fetch the first page of submissions (ordered by most-recent first by default)
  HTTP_CODE=$(curl -s -w '%{http_code}' -o "$RESP_FILE" \
    -b "$COOKIE_JAR" \
    -X POST "${BASE_URL}/question/question_submit/list/page" \
    -H 'Content-Type: application/json' \
    -d '{"current":1,"pageSize":5,"questionId":'$QUESTION_ID'}')

  if [ "$HTTP_CODE" -ne 200 ]; then
    log "[${RESULT_ELAPSED}s] Query returned HTTP $HTTP_CODE — retrying..."
    continue
  fi

  RESP_BODY=$(cat "$RESP_FILE")
  RESP_CODE=$(echo "$RESP_BODY" | jq_val '.code')

  if [ "$RESP_CODE" != "0" ]; then
    RESP_MSG=$(echo "$RESP_BODY" | jq_val '.message' 'unknown')
    log "[${RESULT_ELAPSED}s] Query error code=$RESP_CODE, msg=$RESP_MSG — retrying..."
    continue
  fi

  # Find our submission in the records array by matching the id field.
  # jq returns nothing (empty string) if no match is found.
  MATCH=$(echo "$RESP_BODY" | jq -r \
    --argjson sid "$SUBMIT_ID" \
    '.data.records[] | select(.id == $sid)' 2>/dev/null || true)

  if [ -z "$MATCH" ]; then
    log "[${RESULT_ELAPSED}s] Submission $SUBMIT_ID not yet in list — retrying..."
    continue
  fi

  FOUND=true

  RESULT_STATUS=$(echo "$MATCH" | jq_val '.status')
  RESULT_JUDGEINFO=$(echo "$MATCH" | jq -r '.judgeInfo')

  log "[${RESULT_ELAPSED}s] Submission $SUBMIT_ID status = $RESULT_STATUS"

  # Break out of polling once we reach a terminal state
  if [ "$RESULT_STATUS" -eq "$STATUS_SUCCEED" ] || [ "$RESULT_STATUS" -eq "$STATUS_FAILED" ]; then
    break
  fi

  # Continue polling if still WAITING or RUNNING
done

# ─── step 4: assert result ──────────────────────────────────────────────────

log ""
log "=== Step 4: Assert final result ==="

if [ "$FOUND" != "true" ]; then
  die "Timed out after ${POLL_TIMEOUT}s — submission $SUBMIT_ID never appeared in the list."
fi

# Translate numeric status to human-readable form for the report
status_name() {
  case "$1" in
    "$STATUS_WAITING") echo "WAITING"  ;;
    "$STATUS_RUNNING")  echo "RUNNING"  ;;
    "$STATUS_SUCCEED")  echo "SUCCEED"  ;;
    "$STATUS_FAILED")   echo "FAILED"   ;;
    *)                  echo "UNKNOWN($1)" ;;
  esac
}

STATUS_TEXT=$(status_name "$RESULT_STATUS")

if [ "$RESULT_STATUS" -eq "$STATUS_SUCCEED" ] || [ "$RESULT_STATUS" -eq "$STATUS_FAILED" ]; then
  log ""
  log "╔════════════════════════════════════════╗"
  log "║         SMOKE TEST COMPLETE            ║"
  log "╠════════════════════════════════════════╣"
  log "║  Submission ID : $(printf '%-20s' "$SUBMIT_ID") ║"
  log "║  Status        : $(printf '%-20s' "$STATUS_TEXT") ║"
  log "║  Elapsed       : $(printf '%-20s' "${RESULT_ELAPSED}s") ║"
  log "╚════════════════════════════════════════╝"
  log ""

  # Print judge info if available
  if [ -n "$RESULT_JUDGEINFO" ] && [ "$RESULT_JUDGEINFO" != "null" ]; then
    JUDGE_TIME=$(echo "$RESULT_JUDGEINFO" | jq_val '.time' 'N/A')
    JUDGE_MEMORY=$(echo "$RESULT_JUDGEINFO" | jq_val '.memory' 'N/A')
    JUDGE_MSG=$(echo "$RESULT_JUDGEINFO" | jq_val '.message' 'N/A')
    log "Judge Info:"
    log "  time    = ${JUDGE_TIME} ms"
    log "  memory  = ${JUDGE_MEMORY} KB"
    log "  message = ${JUDGE_MSG}"
  else
    log "No judgeInfo available yet (submission may still be queued)."
  fi
else
  # Still in WAITING or RUNNING after timeout — this is a failure
  log ""
  log "╔════════════════════════════════════════╗"
  log "║         SMOKE TEST FAILED              ║"
  log "╠════════════════════════════════════════╣"
  log "║  Submission ID : $(printf '%-20s' "$SUBMIT_ID") ║"
  log "║  Status        : $(printf '%-20s' "$STATUS_TEXT") ║"
  log "║  Elapsed       : $(printf '%-20s' "${RESULT_ELAPSED}s") ║"
  log "╚════════════════════════════════════════╝"
  log ""
  log "Submission did not reach a terminal state within ${POLL_TIMEOUT}s."
  log "The judge may be stuck or the sandbox is not responding."
  exit 1
fi

# Exit with 0 on SUCCEED (2) or FAILED (3) — both are terminal states.
# A verdict of FAILED means judging completed but the solution was wrong;
# that is a valid smoke-test pass because the pipeline worked end-to-end.
exit 0
