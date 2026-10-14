#!/bin/bash
# Usage: tools/stub-check.sh   (from the project root). Type-checks OUR code against hand-written API stubs.
set -e
LIB=${LIB:-/usr/share/java}
OUT=$(mktemp -d)
javac --release 21 -Xlint:all,-deprecation -d $OUT -cp $LIB/gson.jar $(find tools/api-stubs -name '*.java') $(find src/main/java -name '*.java')
echo "stub-check OK (this is NOT a Paper compile)"
