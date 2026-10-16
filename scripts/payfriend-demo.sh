#!/usr/bin/env bash
#
# PayFriend demo: a pretend wallet app using the ledger, step by step.
#
# PayFriend holds money for its users and lets them pay each other for a
# small fee. Every time money moves, it calls the ledger. This script plays
# PayFriend's role and prints each request and the ledger's answer.
#
# Usage:
#   ./mvnw spring-boot:run              # in one terminal (or: ./mvnw spring-boot:test-run)
#   ./scripts/payfriend-demo.sh         # in another
#   PAUSE=1 ./scripts/payfriend-demo.sh # wait for Enter between steps
#   BASE_URL=http://host:8080 ./scripts/payfriend-demo.sh
#
# Safe to run more than once: account names and idempotency keys get a
# unique suffix per run. Requires curl; uses jq to pretty-print if installed.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN="$(date +%Y%m%d-%H%M%S)"

if [ -t 1 ]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
    BOLD=""; DIM=""; GREEN=""; RED=""; YELLOW=""; RESET=""
fi

# Set by call(): the HTTP status, response body and response headers.
STATUS=""
BODY=""
HEADERS=""

step() {
    printf '\n%s━━ %s%s\n' "$BOLD" "$1" "$RESET"
    if [ -n "${2:-}" ]; then
        printf '%s%s%s\n' "$DIM" "$2" "$RESET"
    fi
    if [ "${PAUSE:-0}" = "1" ]; then
        read -r -p "   (press Enter) " _
    fi
}

pretty() {
    if command -v jq >/dev/null 2>&1; then
        printf '%s\n' "$1" | jq . 2>/dev/null || printf '%s\n' "$1"
    else
        printf '%s\n' "$1"
    fi
}

# call METHOD PATH [IDEMPOTENCY_KEY] [JSON_BODY]
call() {
    local method="$1" path="$2" key="${3:-}" body="${4:-}"
    local args=(-s -X "$method" "$BASE_URL$path" -D - -o /tmp/payfriend-body.$$)
    if [ -n "$key" ]; then
        args+=(-H "Idempotency-Key: $key")
    fi
    if [ -n "$body" ]; then
        args+=(-H "Content-Type: application/json" -d "$body")
    fi

    printf '%s→ %s %s%s' "$YELLOW" "$method" "$path" "$RESET"
    if [ -n "$key" ]; then
        printf '%s   Idempotency-Key: %s%s' "$DIM" "$key" "$RESET"
    fi
    printf '\n'

    HEADERS="$(curl "${args[@]}" | tr -d '\r')"
    BODY="$(cat /tmp/payfriend-body.$$)"
    rm -f /tmp/payfriend-body.$$
    STATUS="$(printf '%s\n' "$HEADERS" | awk 'NR==1 {print $2}')"

    local color="$GREEN"
    case "$STATUS" in 4*|5*) color="$RED" ;; esac
    printf '%s← %s%s' "$color" "$STATUS" "$RESET"
    local replayed
    replayed="$(printf '%s\n' "$HEADERS" | awk -F': ' 'tolower($1)=="idempotent-replayed" {print $2}')"
    if [ -n "$replayed" ]; then
        printf '   Idempotent-Replayed: %s' "$replayed"
    fi
    printf '\n'
    pretty "$BODY"
}

# The "id" of the last response (every success body starts with {"id":N).
last_id() {
    printf '%s' "$BODY" | sed -E 's/^\{"id":([0-9]+).*/\1/'
}

balance_of() {
    curl -s "$BASE_URL/accounts/$1" | sed -E 's/.*"balance":(-?[0-9]+).*/\1/'
}

dollars() {
    local cents="$1" sign=""
    if [ "$cents" -lt 0 ]; then sign="-"; cents=$(( -cents )); fi
    printf '%s$%d.%02d' "$sign" $(( cents / 100 )) $(( cents % 100 ))
}

create_account() {
    local name="$1" type="$2"
    call POST /accounts "" "{\"name\":\"$name ($RUN)\",\"type\":\"$type\",\"currency\":\"CAD\"}" >&2
    last_id
}

posting() {
    printf '{"accountId":%s,"direction":"%s","amount":%s}' "$1" "$2" "$3"
}

entry() {
    local description="$1"; shift
    local lines
    lines="$(IFS=,; echo "$*")"
    printf '{"description":"%s","postings":[%s]}' "$description" "$lines"
}

# --- 0. Is the ledger running? ----------------------------------------------

if ! curl -s -o /dev/null "$BASE_URL/actuator/health"; then
    printf '%sThe ledger is not reachable at %s.%s\n' "$RED" "$BASE_URL" "$RESET"
    printf 'Start it first:  docker compose up -d && ./mvnw spring-boot:run\n'
    printf '   or (no compose): ./mvnw spring-boot:test-run\n'
    exit 1
fi

printf '%sPayFriend × Double-Ledger demo%s  (run %s, ledger at %s)\n' "$BOLD" "$RESET" "$RUN" "$BASE_URL"
printf 'Amounts are in cents: 10000 = $100.00\n'

# --- 1. Chart of accounts -----------------------------------------------------

step "1. PayFriend sets up its accounts" \
     "Bank = real money PayFriend holds (ASSET). Fees = what it earns (REVENUE).
User wallets are LIABILITIES: PayFriend owes that money to its users."
BANK="$(create_account "PayFriend bank" ASSET)"
FEES="$(create_account "PayFriend fees" REVENUE)"
ALICE="$(create_account "Alice wallet" LIABILITY)"
BOB="$(create_account "Bob wallet" LIABILITY)"
printf '\nAccounts: bank=%s fees=%s alice=%s bob=%s\n' "$BANK" "$FEES" "$ALICE" "$BOB"

# --- 2. Deposit ---------------------------------------------------------------

step "2. Alice deposits \$100.00 from her bank card" \
     "The bank account grows (debit an ASSET) and PayFriend now owes Alice \$100 (credit a LIABILITY)."
call POST /journal-entries "deposit-$RUN" \
    "$(entry "Alice deposits" "$(posting "$BANK" DEBIT 10000)" "$(posting "$ALICE" CREDIT 10000)")"

# --- 3. Payment with a fee ----------------------------------------------------

PAYMENT_BODY="$(entry "Alice pays Bob" \
    "$(posting "$ALICE" DEBIT 3100)" "$(posting "$BOB" CREDIT 3000)" "$(posting "$FEES" CREDIT 100)")"

step "3. Alice sends Bob \$30.00; PayFriend keeps a \$1.00 fee" \
     "One entry, three lines: debits 3100 = credits 3000 + 100."
call POST /journal-entries "payment-$RUN" "$PAYMENT_BODY"
PAYMENT_ID="$(last_id)"

# --- 4. Network retry ---------------------------------------------------------

step "4. The response was lost, so PayFriend retries with the SAME key" \
     "The ledger replays the original answer. Nothing is posted twice."
call POST /journal-entries "payment-$RUN" "$PAYMENT_BODY"
printf 'Alice still has %s (not charged twice)\n' "$(dollars "$(balance_of "$ALICE")")"

# --- 5. Insufficient funds ----------------------------------------------------

step "5. Alice tries to send Bob \$80.00 but only has \$69.00" \
     "Rejected with 422. PayFriend shows 'Insufficient funds' and does not retry."
call POST /journal-entries "payment2-$RUN" \
    "$(entry "Alice pays Bob again" "$(posting "$ALICE" DEBIT 8000)" "$(posting "$BOB" CREDIT 8000)")"

# --- 6. Fraud reversal --------------------------------------------------------

step "6. The \$30 payment turns out to be fraud: PayFriend reverses it" \
     "A new entry with every line flipped. The original stays in history."
call POST "/journal-entries/$PAYMENT_ID/reversal" "reverse-$RUN"

step "7. A second reversal of the same payment is refused" \
     "An entry can be reversed only once (409)."
call POST "/journal-entries/$PAYMENT_ID/reversal" "reverse-again-$RUN"

# --- 8. Withdrawal ------------------------------------------------------------

step "8. Alice withdraws \$50.00 to her bank card"
call POST /journal-entries "withdraw-$RUN" \
    "$(entry "Alice withdraws" "$(posting "$ALICE" DEBIT 5000)" "$(posting "$BANK" CREDIT 5000)")"

# --- 9. Statement -------------------------------------------------------------

step "9. Alice opens her transaction history" "Newest first. The payment and its reversal are both visible."
call GET "/accounts/$ALICE/postings"

# --- 10. The books balance ------------------------------------------------------

step "10. The accountant checks the books" \
     "What PayFriend holds (assets) must equal what it owes users (liabilities) plus what it earned (revenue)."
B_BANK="$(balance_of "$BANK")"
B_ALICE="$(balance_of "$ALICE")"
B_BOB="$(balance_of "$BOB")"
B_FEES="$(balance_of "$FEES")"
printf '  Bank          %10s\n' "$(dollars "$B_BANK")"
printf '  Alice wallet  %10s\n' "$(dollars "$B_ALICE")"
printf '  Bob wallet    %10s\n' "$(dollars "$B_BOB")"
printf '  Fees earned   %10s\n' "$(dollars "$B_FEES")"
OWED=$(( B_ALICE + B_BOB + B_FEES ))
if [ "$B_BANK" -eq "$OWED" ]; then
    printf '%s  ✔ Assets %s = liabilities + revenue %s%s\n' "$GREEN" "$(dollars "$B_BANK")" "$(dollars "$OWED")" "$RESET"
else
    printf '%s  ✘ Books do not balance: %s vs %s%s\n' "$RED" "$(dollars "$B_BANK")" "$(dollars "$OWED")" "$RESET"
    exit 1
fi
