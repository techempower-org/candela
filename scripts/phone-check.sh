#!/usr/bin/env bash
# Phone-checker — replaces the 39K-token subagent with a 500-token script.
# Usage: ./scripts/phone-check.sh <device-serial> <versionCode>
set -euo pipefail

DEVICE="${1:?usage: phone-check.sh <device-serial> <versionCode>}"
EXPECTED_VCODE="${2:?usage: phone-check.sh <device-serial> <versionCode>}"

echo "=== Phone check: $DEVICE ==="

# 1. Version
INFO=$(adb -s "$DEVICE" shell dumpsys package org.techempower.candela | grep -E "versionCode|versionName")
ACTUAL_VCODE=$(echo "$INFO" | grep -oP 'versionCode=\K\d+')
ACTUAL_VNAME=$(echo "$INFO" | grep -oP 'versionName=\K\S+')
echo "Version: $ACTUAL_VNAME (code $ACTUAL_VCODE)"
if [ "$ACTUAL_VCODE" != "$EXPECTED_VCODE" ]; then
    echo "FAIL: expected versionCode=$EXPECTED_VCODE, got $ACTUAL_VCODE"
    exit 1
fi

# 2. Launch
adb -s "$DEVICE" shell am start -n org.techempower.candela/in.jphe.storyvox.MainActivity > /dev/null 2>&1
sleep 3

# 3. Crash check (#1726)
# A crash is a real crash signature, not any log line containing "exception":
# W-level library noise (e.g. ML Kit's ComponentDiscovery NoSuchMethodException
# stacks, #1727) used to trip this and fail a healthy launch. Scope to the
# launched PID for its whole lifetime (no -t window — the old 30-line window
# made the result depend on what else the device logged that second).
PID=$(adb -s "$DEVICE" shell pidof org.techempower.candela 2>/dev/null | tr -d '\r' || true)
if [ -z "$PID" ]; then
    echo "FAIL: process not running after launch"
    adb -s "$DEVICE" logcat -d 2>/dev/null \
        | grep -E 'FATAL EXCEPTION|AndroidRuntime.*org\.techempower\.candela|Process org\.techempower\.candela .* has died' | head -8
    exit 1
fi

FATAL=$(adb -s "$DEVICE" logcat -d --pid="$PID" 2>/dev/null \
    | grep -cE 'FATAL EXCEPTION|AndroidRuntime: .*org\.techempower\.candela' || true)
if [ "$FATAL" -gt 0 ]; then
    echo "FAIL: $FATAL fatal crash lines in logcat (PID $PID)"
    adb -s "$DEVICE" logcat -d --pid="$PID" \
        | grep -E -A6 'FATAL EXCEPTION|AndroidRuntime: .*org\.techempower\.candela' | head -20
    exit 1
fi

# Informational only — non-fatal exceptions at W/E for the human to eyeball.
NOISE=$(adb -s "$DEVICE" logcat -d --pid="$PID" 2>/dev/null \
    | grep -cE '^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [WE] .*java\.lang\.\w+(Exception|Error)' || true)
[ "$NOISE" -gt 0 ] && echo "note: $NOISE non-fatal W/E exception lines (not a failure) — first:" \
    && adb -s "$DEVICE" logcat -d --pid="$PID" \
        | grep -E '^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [WE] .*java\.lang\.\w+(Exception|Error)' | head -2 | cut -c1-160

echo "PASS: v$ACTUAL_VNAME running (PID $PID), no crashes"
