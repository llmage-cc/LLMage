#!/usr/bin/env bash
# Shared-layout parity: the replay viewer and the live board mount ONE layout shell.
# See scripts/verify-shared-layout-parity.ts for what each check guards.
set -e
cd "$(dirname "$0")"
flock /tmp/xmage-npm.lock npx tsx verify-shared-layout-parity.ts
