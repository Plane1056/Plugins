#!/usr/bin/env sh
set -eu
mvn --batch-mode --no-transfer-progress clean verify
