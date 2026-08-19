#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/app/build/outputs/apk/release/app-release-signed.apk"
CONFIG_DIR="$SCRIPT_DIR/configs"
DEFAULTS_FILE="$CONFIG_DIR/defaults.yaml"
SUBNET="192.0.2"
PORT=5555
NEW_PKG="pro.nspanel.ha2"
# Removed on every device generation ("pro.nspanel.ha" is the previous-gen app)
REMOVE_PKGS_COMMON=("io.homeassistant.companion.android" "io.homeassistant.companion.android.minimal" "pro.nspanel.ha")
# Extra removals for Gen1 panels (Android 8.1 / SDK <= 28)
REMOVE_PKGS_GEN1=("com.seaky.nspanelpro.tools")
# Extra removals for Gen2 panels (Android 11 / SDK >= 30) — extend after auditing a real device
REMOVE_PKGS_GEN2=("org.fdroid.fdroid" "org.fdroid.fdroid.privileged")

# ── YAML helpers ──────────────────────────────────────────────────────────────

# Read a single top-level key from a flat YAML file.
yaml_get() {
    local file="$1" key="$2"
    grep -E "^${key}:[[:space:]]*" "$file" \
        | sed 's/^[^:]*:[[:space:]]*//' \
        | sed 's/[[:space:]]*#.*//' \
        | tr -d '"'"'" \
        | tr -d '\r' \
        | head -1
}

# ── Deploy helpers ────────────────────────────────────────────────────────────

adb_serial() { echo "${SUBNET}.${1}:${PORT}"; }

pkg_installed() {
    local serial="$1" pkg="$2"
    adb -s "$serial" shell pm list packages 2>/dev/null | grep -qF "package:${pkg}"
}

uninstall_pkg() {
    local serial="$1" pkg="$2"
    if ! pkg_installed "$serial" "$pkg"; then
        echo "    [skip] $pkg not installed"
        return 0
    fi
    echo "    [remove] $pkg"
    if adb -s "$serial" shell pm uninstall "$pkg" 2>/dev/null | grep -q "Success"; then
        return 0
    fi
    # Device-admin fallback
    adb -s "$serial" shell pm disable-user --user 0 "$pkg" 2>/dev/null || true
    adb -s "$serial" shell pm uninstall --user 0 "$pkg" 2>/dev/null || true
}

push_config() {
    local serial="$1" cfg="$2"

    local ha_url screen_brightness screen_timeout idle_dim auto_brightness proximity_wake report_interval show_status_bar yaml_url diag_port
    ha_url=$(yaml_get "$cfg" "ha_url")
    diag_port=$(yaml_get "$cfg" "diag_port")
    yaml_url=$(yaml_get "$cfg" "yaml_url")
    screen_brightness=$(yaml_get "$cfg" "screen_brightness")
    screen_timeout=$(yaml_get "$cfg" "screen_timeout_seconds")
    idle_dim=$(yaml_get "$cfg" "idle_dim_percent")
    auto_brightness=$(yaml_get "$cfg" "auto_brightness")
    proximity_wake=$(yaml_get "$cfg" "proximity_wake")
    report_interval=$(yaml_get "$cfg" "report_interval_seconds")
    show_status_bar=$(yaml_get "$cfg" "show_status_bar")

    local cmd="am broadcast -a pro.nspanel.ha2.PUSH_CONFIG -n pro.nspanel.ha2/.ConfigPushReceiver"
    [[ -n "$ha_url"           ]] && cmd+=" --es ha_url \"$ha_url\""
    [[ -n "$yaml_url"         ]] && cmd+=" --es yaml_url \"$yaml_url\""
    [[ -n "$screen_brightness" ]] && cmd+=" --ei screen_brightness $screen_brightness"
    [[ -n "$screen_timeout"   ]] && cmd+=" --ei screen_timeout_seconds $screen_timeout"
    [[ -n "$idle_dim"         ]] && cmd+=" --ei idle_dim_percent $idle_dim"
    [[ -n "$report_interval"  ]] && cmd+=" --ei report_interval_seconds $report_interval"
    [[ -n "$auto_brightness"  ]] && cmd+=" --ez auto_brightness $auto_brightness"
    [[ -n "$proximity_wake"   ]] && cmd+=" --ez proximity_wake $proximity_wake"
    [[ -n "$show_status_bar"  ]] && cmd+=" --ez show_status_bar $show_status_bar"
    [[ -n "$diag_port"        ]] && cmd+=" --ei diag_port $diag_port"

    adb -s "$serial" shell "$cmd"
}

ensure_running() {
    local serial="$1"
    if adb -s "$serial" shell pidof "$NEW_PKG" 2>/dev/null | grep -qE '[0-9]'; then
        echo "    [running] already active"
    else
        echo "    [launch] $NEW_PKG"
        adb -s "$serial" shell am start -n "${NEW_PKG}/.MainActivity" 2>/dev/null || true
    fi
}

deploy_to() {
    local ip_suffix="$1"
    local serial; serial="$(adb_serial "$ip_suffix")"
    local ip="${SUBNET}.${ip_suffix}"
    local cfg_file="$CONFIG_DIR/${ip}.yaml"

    echo ""
    echo "══════════════════════════════════════════"
    echo " Device: $ip"
    echo "══════════════════════════════════════════"

    # Connect
    adb connect "$serial" 2>&1 | head -1

    if ! adb -s "$serial" get-state 2>/dev/null | grep -q "device"; then
        echo "    [skip] not reachable"
        return 0
    fi

    local model sdk
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
    sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo 0)
    [[ "$sdk" =~ ^[0-9]+$ ]] || sdk=0
    echo "    Model: $model (SDK $sdk)"

    # ── Step 1: Remove old apps (list depends on panel generation) ────────────
    local remove_pkgs=("${REMOVE_PKGS_COMMON[@]}")
    if (( sdk >= 30 )); then
        remove_pkgs+=("${REMOVE_PKGS_GEN2[@]}")
    else
        remove_pkgs+=("${REMOVE_PKGS_GEN1[@]}")
    fi
    for pkg in "${remove_pkgs[@]}"; do
        uninstall_pkg "$serial" "$pkg"
    done

    # ── Step 2: Install / upgrade our app ────────────────────────────────────
    # Streamed install (adb install) — pushing to /sdcard and running pm install
    # fails under Android 11 scoped storage on Gen2 panels.
    echo "    [install] adb install -r"
    adb -s "$serial" install -r "$APK"

    # Grant WRITE_SETTINGS so hardware brightness control works
    adb -s "$serial" shell appops set "$NEW_PKG" WRITE_SETTINGS allow 2>/dev/null || true
    # Grant SYSTEM_ALERT_WINDOW (no overlay drawn) — exempts BootReceiver's
    # startActivity from Android 10+ background-launch restrictions
    adb -s "$serial" shell appops set "$NEW_PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
    # Make the app the default HOME launcher: kiosk mode + guaranteed boot launch on Android 11
    adb -s "$serial" shell cmd package set-home-activity "${NEW_PKG}/.MainActivity" 2>/dev/null || \
        echo "    [warn] set-home-activity failed"

    # ── Step 3: Create per-device config if missing ───────────────────────────
    if [[ ! -f "$cfg_file" ]]; then
        echo "    [config] no config found — creating $cfg_file from defaults"
        cp "$DEFAULTS_FILE" "$cfg_file"
    else
        echo "    [config] using $cfg_file"
    fi

    # ── Step 4: Push config to the app ───────────────────────────────────────
    echo "    [push-config] broadcasting settings"
    push_config "$serial" "$cfg_file"

    ensure_running "$serial"

    # Best-effort diagnostics health check (GET /healthz)
    local diag_port
    diag_port=$(yaml_get "$cfg_file" "diag_port")
    diag_port="${diag_port:-8377}"
    if [[ "$diag_port" != "0" ]] && command -v curl &>/dev/null; then
        sleep 2
        if [[ "$(curl -s -m 3 "http://${ip}:${diag_port}/healthz" 2>/dev/null)" == "ok" ]]; then
            echo "    [health] OK — http://${ip}:${diag_port}/diag"
        else
            echo "    [health] WARN: no response on http://${ip}:${diag_port}/healthz"
        fi
    fi

    echo "    [done] $ip"
}

# ── Scan ──────────────────────────────────────────────────────────────────────

echo "Scanning ${SUBNET}.0/24 for ADB on port ${PORT}..."

if command -v nmap &>/dev/null; then
    HOSTS=$(nmap -p "$PORT" --open -oG - "${SUBNET}.0/24" 2>/dev/null \
        | awk '/Ports:.*open/{print $2}' \
        | grep -oE '[0-9]+$')
else
    HOSTS=""
    for i in $(seq 1 254); do
        if nc -z -w1 "${SUBNET}.${i}" "$PORT" 2>/dev/null; then
            HOSTS="$HOSTS $i"
        fi
    done
    HOSTS=$(echo "$HOSTS" | tr ' ' '\n' | grep -v '^$')
fi

if [ -z "$HOSTS" ]; then
    echo "No hosts found with port $PORT open."
    exit 0
fi

echo "Found hosts (last octet): $HOSTS"

for suffix in $HOSTS; do
    deploy_to "$suffix"
done

echo ""
echo "All done."
