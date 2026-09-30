#!/usr/bin/env bash
# candela-verify — drive Candela on a real device or the API29 emulator over adb.
# One verb per call; every verb exits non-zero on failure so it can gate a check.
#
#   verify.sh emu-boot | emu-kill              headless candela-api29 on :5580 (PID file)
#   verify.sh install [vX.Y.Z | path.apk]      released APK via `gh release download` (default: latest)
#   verify.sh start                            cold start (force-stop + am start -W), clears the crash buffer
#   verify.sh health                           pidof + 0 FATAL in `logcat -b crash` + resumed activity
#   verify.sh nav <screen>                     playing|library|browse|voices|settings|techempower|handbook|notes|briefing|feed
#   verify.sh tap <label> [--contains] [--nth N]  tap the Nth node whose text/content-desc matches (auto-scrolls)
#   verify.sh scroll [down|up]                 one swipe
#   verify.sh wait <label> [secs]              wait until a label is on screen (default 10 s)
#   verify.sh type <text>                      type into the focused field
#   verify.sh key <KEYCODE_…|back|home|enter>  send a key event
#   verify.sh text                             on-screen text (uiautomator dump), one node per line
#   verify.sh shot <file.png>                  screenshot via exec-out (validated PNG)
#   verify.sh stop                             force-stop the app
#   verify.sh smoke [dir]                      start + health + a Home screenshot into dir
#
# Device: $CANDELA_SERIAL, else emulator-5580, else the tablet R83W80CAFZB (USB serial,
# mDNS adb-wifi name, or a connected ip:port whose ro.serialno is R83W80CAFZB). Never runs `adb usb` / `adb tcpip`: toggling modes turns the
# tablet's Wireless debugging off. Builds nothing: unreleased changes come from CI
# artifacts, not a local gradle run.
set -euo pipefail

PKG=org.techempower.candela
ACTIVITY=in.jphe.storyvox.MainActivity
REPO=techempower-org/candela
AVD=candela-api29
EMU_PORT=5580
STATE="${CANDELA_VERIFY_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/candela-verify}"
mkdir -p "$STATE"

die() { echo "verify: $*" >&2; exit 1; }

serial() {
  if [ -n "${CANDELA_SERIAL:-}" ]; then echo "$CANDELA_SERIAL"; return; fi
  local listed devs
  listed=$(timeout 20 adb devices) || die "adb devices failed or timed out (adb server stuck? try: adb kill-server)"
  devs=$(awk 'NR>1 && $2=="device" {print $1}' <<<"$listed")
  if grep -qx "emulator-$EMU_PORT" <<<"$devs"; then echo "emulator-$EMU_PORT"; return; fi
  if grep -qx "R83W80CAFZB" <<<"$devs"; then echo "R83W80CAFZB"; return; fi
  # The tablet over Wireless debugging shows up as adb-R83W80CAFZB-…._adb-tls-connect._tcp
  # (mDNS) or as a bare ip:port. The mDNS name carries the serial; for ip:port, ask the
  # device itself (ro.serialno) — read-only, and never `adb connect`/`pair`/`tcpip`.
  local d wifi; wifi=$(grep -m1 "R83W80CAFZB" <<<"$devs" || true)
  [ -n "$wifi" ] && { echo "$wifi"; return; }
  for d in $(grep -E '^[0-9.]+:[0-9]+$' <<<"$devs" || true); do
    if [ "$(timeout 10 adb -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r' || true)" = R83W80CAFZB ]; then
      echo "$d"; return
    fi
  done
  die "no device: boot the emulator (verify.sh emu-boot) or set CANDELA_SERIAL"
}

# Every device call is time-boxed: some adb subcommands (logcat, wait-for) block forever
# on a serial that is absent or offline instead of failing.
A() { timeout "${CANDELA_VERIFY_ADB_TIMEOUT:-90}" adb -s "$SERIAL" "$@"; }

# uiautomator dump → "text<TAB>desc<TAB>cx<TAB>cy" per labelled node (centre of bounds).
dump_xml() {
  local xml
  A shell uiautomator dump /sdcard/candela-verify-ui.xml >/dev/null 2>&1 || die "uiautomator dump failed"
  xml=$(A exec-out cat /sdcard/candela-verify-ui.xml)
  grep -q '<hierarchy' <<<"$xml" || die "uiautomator dump is empty (Waydroid's is broken; use a real device or the emulator)"
  printf '%s' "$xml"
}

nodes() {
  local xml
  xml=$(dump_xml)
  python3 -c '
import sys, re, xml.etree.ElementTree as E
root = E.fromstring(sys.stdin.read())
for n in root.iter("node"):
    t = (n.get("text") or "").replace("\t", " ").replace("\n", " ")
    d = (n.get("content-desc") or "").replace("\t", " ").replace("\n", " ")
    if not (t or d):
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
    print(f"{t}\t{d}\t{(x1 + x2) // 2}\t{(y1 + y2) // 2}")
' <<<"$xml"
}

# Centre of the Nth (default 1st) node whose text or desc equals LABEL. With
# --contains, a substring match is the fallback; an exact match always wins (so
# "About" picks the About row, not the "About & Help" header above it).
#   find_node LABEL [--contains] [--nth N]
find_node() {
  local label="$1" contains=0 nth=1; shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --contains) contains=1 ;;
      --nth) nth="${2:?--nth N}"; shift ;;
      *) die "unknown option $1" ;;
    esac
    shift
  done
  nodes | awk -F'\t' -v l="$label" -v c="$contains" -v nth="$nth" '
    # No early `exit`: under pipefail, a producer killed by SIGPIPE would fail the call.
    !found && ($1 == l || $2 == l) && ++e == nth { exact_xy = $3 " " $4; found = 1 }
    c && sub_xy == "" && (index($1, l) || index($2, l)) && ++k == nth { sub_xy = $3 " " $4 }
    END { if (found) print exact_xy; else if (sub_xy != "") print sub_xy }'
}

# Current screen size in INPUT coordinates, from the uiautomator root node's bounds:
# they follow rotation, unlike `wm size` (physical/override, always portrait-native),
# and they are the same coordinate space taps use.
screen_size() {
  dump_xml | python3 -c '
import sys, re, xml.etree.ElementTree as E
n = E.fromstring(sys.stdin.read()).find("node")
x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
print(x2, y2)'
}

cmd_scroll() {
  local dir="${1:-down}" w h size
  size=$(screen_size) || die "scroll: could not read the screen bounds"
  read -r w h <<<"$size"
  [[ "$w" =~ ^[1-9][0-9]*$ && "$h" =~ ^[1-9][0-9]*$ ]] || die "scroll: invalid screen bounds \"$size\""
  local x=$((w / 2)) lo=$((h * 3 / 4)) hi=$((h / 4))
  case "$dir" in
    down) A shell input swipe "$x" "$lo" "$x" "$hi" 300 ;;  # reveal content below
    up) A shell input swipe "$x" "$hi" "$x" "$lo" 300 ;;
    *) die "scroll: down|up" ;;
  esac
  sleep 0.8
}

# tap <label> [--contains] [--nth N]: if the label is off-screen, scrolls down up to
# CANDELA_VERIFY_SCROLLS (8) times, then back up twice as far (screens restore their
# old scroll position, so the label can be above as well as below).
cmd_tap() {
  local label="${1:?tap <label>}" xy n max="${CANDELA_VERIFY_SCROLLS:-8}"
  shift
  xy=$(find_node "$label" "$@")
  for ((n = 0; n < max && ${#xy} == 0; n++)); do
    cmd_scroll down
    xy=$(find_node "$label" "$@")
  done
  for ((n = 0; n < 2 * max && ${#xy} == 0; n++)); do
    cmd_scroll up
    xy=$(find_node "$label" "$@")
  done
  [ -n "$xy" ] || die "tap: \"$label\" not on screen (verify.sh text lists what is)"
  # shellcheck disable=SC2086
  A shell input tap $xy
  sleep "${CANDELA_VERIFY_SETTLE:-1.5}"
}

cmd_wait() {
  local label="${1:?wait <label> [secs]}" secs="${2:-10}" end=$((SECONDS + ${2:-10}))
  while [ $SECONDS -lt $end ]; do
    [ -n "$(find_node "$label" --contains)" ] && return 0
    sleep 1
  done
  die "wait: \"$label\" did not appear within ${secs}s"
}

resumed_activity() {
  A shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | tr -d '\r' || true
}

# Bring Candela back to the front WITHOUT a cold start (a Back from the root screen,
# or a 211/Discord link, leaves the app). No-op when it is already in front.
front() {
  grep -q "$PKG/" <<<"$(resumed_activity)" && return 0
  A shell am start -n "$PKG/$ACTIVITY" >/dev/null
  sleep 1.5
  grep -q "$PKG/" <<<"$(resumed_activity)" || die "could not bring $PKG to the front"
}

# Bottom-bar tab. Full-screen pages hide the bar, so back out (up to 4x) until it
# shows — re-fronting Candela if a Back from its root screen closed it.
tab() {
  local label="$1" n
  for ((n = 0; n < 4; n++)); do
    front
    if [ -n "$(find_node Voices)" ] && [ -n "$(find_node Settings)" ]; then
      CANDELA_VERIFY_SCROLLS=0 cmd_tap "$label" --nth "$(tab_nth "$label")"
      return
    fi
    cmd_key back; sleep 0.8
  done
  die "nav: bottom bar not found"
}

# The bar's label is the LAST node with that text (a screen title can share it).
tab_nth() { nodes | awk -F'\t' -v l="$1" '$1 == l { n++ } END { print n ? n : 1 }'; }

cmd_nav() {
  local screen="${1:?nav <screen>}"
  case "$screen" in
    playing) tab Playing ;;
    library | home) tab Library ;;
    browse) tab Browse ;;
    voices) tab Voices ;;
    settings) tab Settings ;;
    techempower) tab Library; cmd_tap TechEMPOWER ;;
    notes) tab Library; cmd_tap "Voice Notes" ;;
    handbook) tab Settings; cmd_tap About; cmd_tap "Read the handbook" ;;
    briefing) tab Settings; cmd_tap "Morning Briefing" ;;
    feed) tab Settings; cmd_tap "For you feed" ;;
    *) die "nav: unknown screen \"$screen\" (playing|library|browse|voices|settings|techempower|notes|handbook|briefing|feed)" ;;
  esac
}

cmd_type() {
  local s="${1:?type <text>}"
  # `input text` treats space as an argument break and mangles % and #; escape them.
  case "$s" in *%* | *\#*) die "type: % and # are mangled by 'input text'; avoid them" ;; esac
  s=${s// /%s}
  s=$(printf '%q' "$s")
  A shell input text "$s"
}

cmd_key() {
  local k="${1:?key <KEYCODE>}"
  case "$k" in back) k=KEYCODE_BACK ;; home) k=KEYCODE_HOME ;; enter) k=KEYCODE_ENTER ;; esac
  A shell input keyevent "$k"
}

cmd_shot() {
  local out="${1:?shot <file.png>}"
  A exec-out screencap -p >"$out"
  # A PNG starts \x89PNG; a shell-side screencap-to-file-then-cat can corrupt it (Flip3).
  [ "$(head -c 4 "$out" | od -An -tx1 | tr -d ' ')" = 89504e47 ] || die "shot: $out is not a PNG"
  echo "$out"
}

cmd_start() {
  A logcat -b crash -c || true
  A shell am force-stop "$PKG"
  A shell am start -W -n "$PKG/$ACTIVITY" | grep -E 'Status|LaunchState|TotalTime' || true
  local _
  for _ in $(seq 1 20); do
    [ -n "$(A shell pidof "$PKG" | tr -d '\r')" ] && return 0
    sleep 0.5
  done
  die "start: $PKG has no process 10 s after launch"
}

cmd_health() {
  local pid fatal resumed ok=0
  pid=$(A shell pidof "$PKG" | tr -d '\r' || true)
  # The crash buffer holds only crashes (no library noise); start clears it. Read it with a
  # checked status first: a failed read must FAIL health, not count as "0 FATAL".
  local crash
  if ! crash=$(A logcat -b crash -d); then
    echo "FAIL: could not read the crash buffer" >&2
    return 1
  fi
  fatal=$(grep -c "FATAL EXCEPTION" <<<"$crash" || true)
  resumed=$(resumed_activity)
  echo "pid:     ${pid:-NONE}"
  echo "fatal:   $fatal"
  resumed=$(sed -E 's/.* u0 //; s/ t[0-9]+\}?$//' <<<"$resumed")
  echo "resumed: ${resumed:-NONE}"
  [ -n "$pid" ] || { echo "FAIL: no process" >&2; ok=1; }
  [ "$fatal" -eq 0 ] || { head -20 <<<"$crash" >&2; echo "FAIL: $fatal FATAL" >&2; ok=1; }
  grep -q "$PKG/" <<<"$resumed" || { echo "FAIL: Candela is not the resumed activity" >&2; ok=1; }
  return $ok
}

cmd_install() {
  local what="${1:-}" apk
  if [ -f "$what" ]; then apk="$what"
  else
    local tag="$what"
    [ -n "$tag" ] || tag=$(gh release view --repo "$REPO" --json tagName --jq .tagName)
    apk="$STATE/candela-$tag.apk"
    [ -s "$apk" ] || gh release download "$tag" --repo "$REPO" --pattern "candela-$tag.apk" --output "$apk" --clobber
  fi
  CANDELA_VERIFY_ADB_TIMEOUT=600 A install -r "$apk"   # ~190 MB; slow over Wi-Fi
  # Capture first: `grep -m1` exiting early would SIGPIPE dumpsys and trip pipefail.
  local pkg; pkg=$(A shell dumpsys package "$PKG")
  grep -m1 versionName <<<"$pkg" | tr -d ' \r'
}

cmd_emu_boot() {
  SERIAL="emulator-$EMU_PORT"
  # An already-listed emulator may be offline or still booting: poll it below either way.
  local listed; listed=$(timeout 20 adb devices) || die "emu-boot: adb devices failed or timed out"
  if ! grep -q "^emulator-$EMU_PORT" <<<"$listed"; then
    local emu="${ANDROID_HOME:-$HOME/Android/Sdk}/emulator/emulator"
    [ -x "$emu" ] || die "emu-boot: no emulator at $emu"
    nohup "$emu" -avd "$AVD" -port "$EMU_PORT" -no-window -no-audio -gpu swiftshader_indirect \
      -no-snapshot-save -no-boot-anim >"$STATE/emu.log" 2>&1 &
    echo $! >"$STATE/emu.pid"
  fi
  local _
  for _ in $(seq 1 60); do
    [ "$(CANDELA_VERIFY_ADB_TIMEOUT=10 A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1 ] && { echo "booted (pid $(cat "$STATE/emu.pid" 2>/dev/null || echo "?"))"; return 0; }
    sleep 3
  done
  die "emu-boot: not booted after 180 s (see $STATE/emu.log)"
}

cmd_emu_kill() {
  [ -f "$STATE/emu.pid" ] || die "emu-kill: no PID file (not started by emu-boot)"
  local pid; pid=$(cat "$STATE/emu.pid")
  # Kill by PID only (a pattern kill such as `pkill -f emulator` matches the calling shell
  # too), and only if that PID is still OUR emulator: a stale file after a crash may name a
  # reused PID.
  local cmdline; cmdline=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)
  if ! grep -q -- "-avd $AVD -port $EMU_PORT" <<<"$cmdline"; then
    rm -f "$STATE/emu.pid"
    die "emu-kill: pid $pid is not the $AVD emulator (stale PID file removed; nothing killed)"
  fi
  kill "$pid"
  local _
  for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  rm -f "$STATE/emu.pid"
  echo "killed emulator pid $pid"
}

cmd_smoke() {
  local dir="${1:-.}"
  mkdir -p "$dir"
  cmd_start
  sleep 3
  cmd_health
  cmd_shot "$dir/home.png"
}

verb="${1:-}"; shift || true
case "$verb" in
  emu-boot) cmd_emu_boot; exit ;;
  emu-kill) cmd_emu_kill; exit ;;
  "" | -h | --help) sed -n '2,20p' "$0"; exit 0 ;;
esac
SERIAL=$(serial)
case "$verb" in
  install) cmd_install "$@" ;;
  start) cmd_start ;;
  health) cmd_health ;;
  nav) cmd_nav "$@" ;;
  tap) cmd_tap "$@" ;;
  scroll) cmd_scroll "$@" ;;
  wait) cmd_wait "$@" ;;
  type) cmd_type "$@" ;;
  key) cmd_key "$@" ;;
  text) nodes | awk -F'\t' '{ print ($1 != "" ? $1 : "") ($1 != "" && $2 != "" ? " | " : "") ($2 != "" ? "[" $2 "]" : "") }' ;;
  shot) cmd_shot "$@" ;;
  stop) A shell am force-stop "$PKG" ;;
  smoke) cmd_smoke "$@" ;;
  *) die "unknown verb \"$verb\" (verify.sh --help)" ;;
esac
