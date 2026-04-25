#!/usr/bin/env bash
#
# Run every webhook receiver example's test suite. Each suite is independent —
# a missing runtime for one language won't stop the others.
#
# Usage: ./run-all-tests.sh
#
# Exit code: 0 if every available suite passed; 1 if any failed. Suites that
# are skipped because the language toolchain isn't installed do NOT fail the
# script — they're listed as SKIP at the end.

set -u
EXAMPLES_DIR="$(cd "$(dirname "$0")" && pwd)"

# Pick up Homebrew-installed tools (mvn, etc.) on macOS.
if [ -d /opt/homebrew/bin ]; then export PATH="/opt/homebrew/bin:$PATH"; fi
if [ -d /usr/local/share/dotnet ]; then export PATH="/usr/local/share/dotnet:$PATH"; fi

# macOS ships Ruby 2.6 on PATH by default, which is too old for Sinatra 4.
# If a newer Ruby is installed via Homebrew (keg-only on either Intel or
# Apple Silicon prefixes), prefer it over the system one.
for candidate in /opt/homebrew/opt/ruby/bin /usr/local/opt/ruby/bin; do
    if [ -x "$candidate/ruby" ]; then
        if "$candidate/ruby" -e 'exit(Gem::Version.new(RUBY_VERSION) >= Gem::Version.new("3.1"))' 2>/dev/null; then
            export PATH="$candidate:$PATH"
            break
        fi
    fi
done

RESULTS=()
PASS=0
FAIL=0
SKIP=0

cyan()  { printf '\033[36m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
amber() { printf '\033[33m%s\033[0m\n' "$*"; }

run_suite() {
    local label="$1"
    local dir="$2"
    shift 2

    cyan "============================================================"
    cyan "  $label"
    cyan "  $dir"
    cyan "============================================================"

    if (cd "$dir" && "$@"); then
        green "  ✓ $label PASSED"
        RESULTS+=("PASS  $label")
        PASS=$((PASS + 1))
    else
        red "  ✗ $label FAILED"
        RESULTS+=("FAIL  $label")
        FAIL=$((FAIL + 1))
    fi
    echo
}

skip_suite() {
    local label="$1"
    local reason="$2"
    amber "------------------------------------------------------------"
    amber "  SKIP $label — $reason"
    amber "------------------------------------------------------------"
    echo
    RESULTS+=("SKIP  $label ($reason)")
    SKIP=$((SKIP + 1))
}

# ---------------------------------------------------------------------------
# Node.js
# ---------------------------------------------------------------------------
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    NODE_DIR="$EXAMPLES_DIR/node"
    if [ ! -d "$NODE_DIR/node_modules" ]; then
        cyan "[node] installing dependencies…"
        (cd "$NODE_DIR" && npm install --no-audit --no-fund --silent) || true
    fi
    run_suite "Node.js" "$NODE_DIR" npm test --silent
else
    skip_suite "Node.js" "node/npm not found"
fi

# ---------------------------------------------------------------------------
# Java (Spring Boot)
# ---------------------------------------------------------------------------
if command -v mvn >/dev/null 2>&1; then
    run_suite "Java" "$EXAMPLES_DIR/java" mvn -q test
else
    skip_suite "Java" "mvn not found (install Maven, e.g. brew install maven)"
fi

# ---------------------------------------------------------------------------
# C# (ASP.NET Core)
# ---------------------------------------------------------------------------
if command -v dotnet >/dev/null 2>&1; then
    DOTNET_FRAMEWORK_ARGS=()
    RUNTIMES="$(dotnet --list-runtimes 2>/dev/null)"
    if echo "$RUNTIMES" | grep -q '^Microsoft\.AspNetCore\.App 8\.'; then
        : # net8.0 runtime is available; let dotnet pick.
    elif echo "$RUNTIMES" | grep -q '^Microsoft\.AspNetCore\.App 9\.'; then
        DOTNET_FRAMEWORK_ARGS=(--framework net9.0)
    fi
    run_suite "C#" "$EXAMPLES_DIR/csharp/LocoAware.Webhook.Tests" \
        dotnet test --nologo "${DOTNET_FRAMEWORK_ARGS[@]}"
else
    skip_suite "C#" "dotnet not found"
fi

# ---------------------------------------------------------------------------
# PHP (Slim)
# ---------------------------------------------------------------------------
if command -v php >/dev/null 2>&1 && command -v composer >/dev/null 2>&1; then
    PHP_DIR="$EXAMPLES_DIR/php"
    if [ ! -d "$PHP_DIR/vendor" ]; then
        cyan "[php] installing dependencies…"
        (cd "$PHP_DIR" && composer install --no-interaction --quiet) || true
    fi
    run_suite "PHP" "$PHP_DIR" vendor/bin/phpunit
else
    skip_suite "PHP" "php and/or composer not found"
fi

# ---------------------------------------------------------------------------
# Ruby (Sinatra) — needs Ruby >= 3.1 (Sinatra 4 requirement).
# ---------------------------------------------------------------------------
if command -v ruby >/dev/null 2>&1 && command -v bundle >/dev/null 2>&1; then
    RUBY_VER="$(ruby -e 'print RUBY_VERSION')"
    if ruby -e 'exit(Gem::Version.new(RUBY_VERSION) >= Gem::Version.new("3.1"))'; then
        RUBY_DIR="$EXAMPLES_DIR/ruby"
        if [ ! -d "$RUBY_DIR/.bundle" ] && [ ! -f "$RUBY_DIR/Gemfile.lock" ]; then
            cyan "[ruby] installing dependencies…"
            (cd "$RUBY_DIR" && bundle install --quiet) || true
        fi
        run_suite "Ruby" "$RUBY_DIR" bundle exec rake test
    else
        skip_suite "Ruby" "ruby $RUBY_VER is too old (Sinatra 4 needs >= 3.1)"
    fi
else
    skip_suite "Ruby" "ruby and/or bundler not found"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
cyan "============================================================"
cyan "  Summary"
cyan "============================================================"
for r in "${RESULTS[@]}"; do
    case "$r" in
        PASS*) green "  $r" ;;
        FAIL*) red   "  $r" ;;
        SKIP*) amber "  $r" ;;
    esac
done
echo
echo "  $PASS passed, $FAIL failed, $SKIP skipped"
echo

[ "$FAIL" -eq 0 ]
