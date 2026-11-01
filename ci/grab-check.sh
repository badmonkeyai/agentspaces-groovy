#!/usr/bin/env bash
# The @Grab job (SRAD GR-D-05, GR-Q-TST-03): the README's three-line script runs from an installed
# Groovy 6 against the artifacts `mvn install` put in the local repository. Grape's engines read
# ~/.m2/repository as a local repository; GRAPE_ROOT isolates the download cache.
#
#   ci/grab-check.sh [path-to-groovy-6-binary]
set -euo pipefail
# The explicit Groovy 6 install first: SDKMAN's GROOVY_HOME may point at another line.
GROOVY=${1:-}
if [ -z "$GROOVY" ]; then
  for candidate in "$HOME/.sdkman/candidates/groovy/6.0.0/bin/groovy" "${GROOVY_HOME:-/nonexistent}/bin/groovy" "$(command -v groovy || true)"; do
    if [ -x "$candidate" ]; then GROOVY=$candidate; break; fi
  done
fi
if [ ! -x "$GROOVY" ]; then
  echo "no Groovy 6 at $GROOVY (set GROOVY_HOME or pass the binary)"; exit 2
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/grab.groovy" <<'SCRIPT'
@Grab('ai.badmonkey.agentspaces:agentspaces-groovy:0.5.0')
import ai.badmonkey.agentspaces.groovy.Fleet
import ai.badmonkey.agentspaces.groovy.FleetScript

def tree = Fleet.tree { bind '127.0.0.1:7591'; tick '250ms'; group('g') { founding 'f'; space 'tasks' } }
assert tree.groups[0].spaces[0].name == 'tasks'
assert tree.'tick-millis' == 250
assert FleetScript.name == 'ai.badmonkey.agentspaces.groovy.FleetScript'
println "grab ok: ${tree}"
SCRIPT
# The launcher checks GROOVY_HOME against its own location; point it at the chosen install.
export GROOVY_HOME
GROOVY_HOME=$(cd "$(dirname "$GROOVY")/.." && pwd)
"$GROOVY" --version
"$GROOVY" -Dgrape.root="$WORK/grape" "$WORK/grab.groovy"
