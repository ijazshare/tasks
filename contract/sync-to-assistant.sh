#!/usr/bin/env bash
# Copies the contract fixtures and the AIDL file into Assistant (spec 13.4). Usage: sync-to-assistant.sh [themachine checkout]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
to="${1:-/home/ijaz/assistant-dev/themachine}"
mkdir -p "$to/app/src/test/resources/tasks-contract/v1" "$to/app/src/main/aidl/io/github/ijazshare/tasks/ipc"
rm -f "$to"/app/src/test/resources/tasks-contract/v1/*
cp "$here"/v1/* "$to/app/src/test/resources/tasks-contract/v1/"
cp "$here/v1/ITaskCommand.aidl" "$to/app/src/main/aidl/io/github/ijazshare/tasks/ipc/ITaskCommand.aidl"
echo "synced to $to"
