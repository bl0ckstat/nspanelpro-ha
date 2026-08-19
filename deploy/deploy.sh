#!/usr/bin/env bash
# NSPanel HA — fleet deployment script
# Scans subnets for ADB devices, installs/updates the app, removes unwanted packages,
# and pushes per-device config. Safe to run repeatedly (idempotent).
# Requirements: nmap, adb (both must be on PATH)
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUBNETS_CONF="${SCRIPT_DIR}/subnets.conf"
PANELS_DIR="${SCRIPT_DIR}/panels"
APK_PATH="${SCRIPT_DIR}/nspanel-pro2-ha.apk"   # build output; place here before running

APP_PACKAGE="pro.nspanel.ha2"
APP_ACTIVITY=".MainActivity"
ADB_PORT=5555

# Packages to remove on every device generation
REMOVE_PACKAGES_COMMON=(
    "io.homeassistant.companion.android"
    "io.homeassistant.companion.android.minimal"
    "pro.nspanel.ha"   # previous-generation app, replaced by pro.nspanel.ha2
)

# Extra removals for Gen1 panels (Android 8.1 / SDK <= 28)
REMOVE_PACKAGES_GEN1=(
    "com.seaky.nspanelpro.tools"
)

# Extra removals for Gen2 panels (Android 11 / SDK >= 30).
# Populate from the [AUDIT] output printed on first run against a Gen2 device.
REMOVE_PACKAGES_GEN2=(
    "org.fdroid.fdroid"
    "org.fdroid.fdroid.privileged"
)

# Packages to remove by prefix match (removes any installed package that starts with these)
REMOVE_PREFIXES=(
    # "com.itead.nspanel"
    # "pro.nspanel.tools"
)

# Default values used when creating a new per-device config file
DEFAULT_HA_URL="http://homeassistant.local:8123"
DEFAULT_BRIGHTNESS=180
DEFAULT_TIMEOUT=120
DEFAULT_PROXIMITY_WAKE=true
DEFAULT_IDLE_DIM=40
DEFAULT_SHOW_STATUS_BAR=false
DEFAULT_SENSOR_INTERVAL=30
DEFAULT_AUTO_BRIGHTNESS=true
DEFAULT_DIAG_PORT=8377

# ── Helpers ───────────────────────────────────────────────────────────────────

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
info() { echo "  $*"; }
warn() { echo "  [WARN] $*"; }
skip() { echo "  [SKIP] $*"; return 0; }

require_tool() {
    command -v "$1" &>/dev/null || { echo "ERROR: '$1' not found on PATH. Install it and retry."; exit 1; }
}

# Read a scalar value from a simple key: value YAML file.
# Strips quotes and inline comments; returns default if key is absent.
yaml_get() {
    local file="$1" key="$2" default="${3:-}"
    local val
    val=$(grep -E "^[[:space:]]*${key}:[[:space:]]" "$file" 2>/dev/null \
        | head -1 \
        | sed 's/^[^:]*:[[:space:]]*//' \
        | sed "s/[[:space:]]*#.*//"     \
        | tr -d "\"'" \
        | tr -d '[:space:]')
    printf '%s' "${val:-$default}"
}

# Scan a CIDR subnet and print IPs that have ADB_PORT open.
scan_subnet() {
    local subnet="$1"
    log "Scanning $subnet for ADB port $ADB_PORT ..." >&2
    nmap -p "$ADB_PORT" --open -T4 -oG - "$subnet" 2>/dev/null \
        | grep "/open/" \
        | awk '{print $2}'
}

# Connect to ip:ADB_PORT; return 0 on success.
adb_connect() {
    local ip="$1"
    local out
    out=$(adb connect "${ip}:${ADB_PORT}" 2>&1)
    echo "$out" | grep -qE "connected to|already connected"
}

# Return the device state string (device / unauthorized / offline / error).
adb_state() {
    adb -s "${1}:${ADB_PORT}" get-state 2>/dev/null || echo "error"
}

# Return 0 if the exact package name is installed.
is_installed() {
    local serial="$1" pkg="$2"
    adb -s "$serial" shell pm list packages 2>/dev/null \
        | grep -qF "package:${pkg}"
}

# Return 0 if the app process is running.
is_running() {
    local serial="$1"
    local result
    result=$(adb -s "$serial" shell "pgrep -f '${APP_PACKAGE}' 2>/dev/null" 2>/dev/null || true)
    [[ -n "$result" ]]
}

# Return 0 if the app currently owns the screen. A live process is not enough:
# a factory-fresh panel keeps the stock launcher (com.eWeLinkControlPanel)
# resumed while our app sits in the background, which leaves the diagnostics
# server unbound and the health check failing for no visible reason.
is_foreground() {
    local serial="$1"
    adb -s "$serial" shell dumpsys activity activities 2>/dev/null \
        | grep -m1 "mResumedActivity" \
        | grep -qF "$APP_PACKAGE"
}

# ── Per-device logic ──────────────────────────────────────────────────────────

# Uninstall with fallbacks for system-partition apps (Gen2 stock apps):
# adb uninstall → pm uninstall --user 0 → pm disable-user --user 0
remove_package() {
    local serial="$1" pkg="$2"
    if adb -s "$serial" uninstall "$pkg" 2>&1 | grep -q "Success"; then
        return 0
    fi
    if adb -s "$serial" shell pm uninstall --user 0 "$pkg" 2>&1 | grep -q "Success"; then
        info "         (removed for user 0 — system app)"
        return 0
    fi
    adb -s "$serial" shell pm disable-user --user 0 "$pkg" >/dev/null 2>&1 \
        && info "         (disabled — could not uninstall)" \
        || warn "Could not remove or disable $pkg"
}

remove_unwanted_packages() {
    local serial="$1" sdk="$2"
    local pkg

    local packages=("${REMOVE_PACKAGES_COMMON[@]}")
    if (( sdk >= 30 )); then
        packages+=("${REMOVE_PACKAGES_GEN2[@]}")
    else
        packages+=("${REMOVE_PACKAGES_GEN1[@]}")
    fi

    for pkg in "${packages[@]}"; do
        if is_installed "$serial" "$pkg"; then
            info "[REMOVE] $pkg"
            remove_package "$serial" "$pkg"
        fi
    done

    if [[ ${#REMOVE_PREFIXES[@]} -gt 0 ]]; then
        local installed
        installed=$(adb -s "$serial" shell pm list packages 2>/dev/null | sed 's/^package://' | tr -d '\r')
        local prefix
        for prefix in "${REMOVE_PREFIXES[@]}"; do
            while IFS= read -r pkg; do
                [[ -z "$pkg" ]] && continue
                info "[REMOVE] $pkg (prefix: $prefix)"
                remove_package "$serial" "$pkg"
            done < <(printf '%s\n' "$installed" | grep -F "$prefix" || true)
        done
    fi
}

# On Gen2 devices, list third-party packages not on any removal list so the
# user can extend REMOVE_PACKAGES_GEN2. Informational only — never auto-removes.
audit_gen2_packages() {
    local serial="$1"
    local known=" ${REMOVE_PACKAGES_COMMON[*]} ${REMOVE_PACKAGES_GEN2[*]} ${APP_PACKAGE} "
    local pkg
    while IFS= read -r pkg; do
        [[ -z "$pkg" ]] && continue
        if [[ "$known" != *" $pkg "* ]]; then
            info "[AUDIT] third-party package present: $pkg"
        fi
    done < <(adb -s "$serial" shell pm list packages -3 2>/dev/null | sed 's/^package://' | tr -d '\r')
}

# Idempotent per-device setup: appops grants and default-HOME launcher.
# WRITE_SETTINGS   → hardware brightness control
# SYSTEM_ALERT_WINDOW → boot-launch exemption on Android 10+ (no overlay is drawn)
# set-home-activity → kiosk mode + guaranteed launch at boot on Android 11
configure_device() {
    local serial="$1"
    info "[CONFIGURE] appops grants + default launcher"
    adb -s "$serial" shell appops set "$APP_PACKAGE" WRITE_SETTINGS allow 2>/dev/null || \
        warn "Could not grant WRITE_SETTINGS"
    adb -s "$serial" shell appops set "$APP_PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null || \
        warn "Could not grant SYSTEM_ALERT_WINDOW"
    local out
    out=$(adb -s "$serial" shell cmd package set-home-activity "${APP_PACKAGE}/${APP_ACTIVITY}" 2>&1 | tr -d '\r')
    if echo "$out" | grep -qi "error"; then
        warn "set-home-activity failed: $out"
    fi
}

install_or_update_app() {
    local serial="$1"

    if [[ ! -f "$APK_PATH" ]]; then
        warn "APK not found at $APK_PATH — skipping install/update"
        warn "Build the app and place the APK there, then re-run."
        return
    fi

    if is_installed "$serial" "$APP_PACKAGE"; then
        info "[UPDATE] Replacing $APP_PACKAGE ..."
    else
        info "[INSTALL] Installing $APP_PACKAGE ..."
    fi
    adb -s "$serial" install -r "$APK_PATH" 2>&1 | tail -1
}

# Poll the app's diagnostics HTTP endpoint (best-effort; needs curl).
health_check() {
    local ip="$1"
    local yaml_file="${PANELS_DIR}/panel-${ip}.yaml"
    local diag_port
    diag_port=$(yaml_get "$yaml_file" "diag_port" "$DEFAULT_DIAG_PORT")
    if [[ "$diag_port" == "0" ]]; then
        info "[HEALTH] diagnostics disabled (diag_port=0)"
        return
    fi
    command -v curl &>/dev/null || { info "[HEALTH] curl not available — skipping"; return; }
    # Give the app a moment to (re)start the server after config push
    local attempt
    for attempt in 1 2 3; do
        if [[ "$(curl -s -m 3 "http://${ip}:${diag_port}/healthz" 2>/dev/null)" == "ok" ]]; then
            info "[HEALTH] OK — http://${ip}:${diag_port}/diag"
            return
        fi
        sleep 2
    done
    warn "Health check failed: http://${ip}:${diag_port}/healthz not responding"
}

ensure_running() {
    local serial="$1"
    if is_foreground "$serial"; then
        info "[OK] App already in foreground"
        return
    fi
    if is_running "$serial"; then
        info "[LAUNCH] Running but backgrounded — bringing to front ..."
    else
        info "[LAUNCH] Starting $APP_PACKAGE ..."
    fi
    adb -s "$serial" shell am start -n "${APP_PACKAGE}/${APP_ACTIVITY}" \
        > /dev/null 2>&1 || true
    # Give the activity a moment to resume and bind the diagnostics port
    sleep 3
    is_foreground "$serial" || warn "$APP_PACKAGE did not come to the foreground"
}

create_panel_config_if_missing() {
    local ip="$1"
    local yaml_file="${PANELS_DIR}/panel-${ip}.yaml"
    if [[ -f "$yaml_file" ]]; then
        return
    fi
    mkdir -p "$PANELS_DIR"
    info "[CREATE] $yaml_file"
    cat > "$yaml_file" <<EOF
# NSPanel HA config for ${ip}
# Edit values here; the deploy script pushes them to the device on each run.

ha_url: "${DEFAULT_HA_URL}"

screen_brightness: ${DEFAULT_BRIGHTNESS}
screen_timeout_seconds: ${DEFAULT_TIMEOUT}
proximity_wake: ${DEFAULT_PROXIMITY_WAKE}
idle_dim_percent: ${DEFAULT_IDLE_DIM}
show_status_bar: ${DEFAULT_SHOW_STATUS_BAR}
report_interval_seconds: ${DEFAULT_SENSOR_INTERVAL}
auto_brightness: ${DEFAULT_AUTO_BRIGHTNESS}
diag_port: ${DEFAULT_DIAG_PORT}

# Optional: set yaml_url to have the device fetch a remote panel config YAML.
# When set, overrides the panel settings above.
# yaml_url: "http://192.0.2.1/panels/${ip}.yaml"
EOF
}

push_config() {
    local serial="$1" ip="$2"
    local yaml_file="${PANELS_DIR}/panel-${ip}.yaml"

    local ha_url brightness timeout proximity_wake idle_dim show_status_bar \
          sensor_interval auto_brightness yaml_url diag_port

    ha_url=$(yaml_get          "$yaml_file" "ha_url"                "$DEFAULT_HA_URL")
    brightness=$(yaml_get      "$yaml_file" "screen_brightness"     "$DEFAULT_BRIGHTNESS")
    timeout=$(yaml_get         "$yaml_file" "screen_timeout_seconds" "$DEFAULT_TIMEOUT")
    proximity_wake=$(yaml_get  "$yaml_file" "proximity_wake"        "$DEFAULT_PROXIMITY_WAKE")
    idle_dim=$(yaml_get        "$yaml_file" "idle_dim_percent"      "$DEFAULT_IDLE_DIM")
    show_status_bar=$(yaml_get "$yaml_file" "show_status_bar"       "$DEFAULT_SHOW_STATUS_BAR")
    sensor_interval=$(yaml_get "$yaml_file" "report_interval_seconds" "$DEFAULT_SENSOR_INTERVAL")
    auto_brightness=$(yaml_get "$yaml_file" "auto_brightness"       "$DEFAULT_AUTO_BRIGHTNESS")
    diag_port=$(yaml_get       "$yaml_file" "diag_port"             "$DEFAULT_DIAG_PORT")
    yaml_url=$(yaml_get        "$yaml_file" "yaml_url"              "")

    info "[CONFIG] Pushing config (ha_url=${ha_url}) ..."

    local broadcast_args=(
        am broadcast
        -a pro.nspanel.ha2.PUSH_CONFIG
        -n "pro.nspanel.ha2/.ConfigPushReceiver"
        --es ha_url "$ha_url"
        --ei screen_brightness "$brightness"
        --ei screen_timeout_seconds "$timeout"
        --ez proximity_wake "$proximity_wake"
        --ei idle_dim_percent "$idle_dim"
        --ez show_status_bar "$show_status_bar"
        --ei report_interval_seconds "$sensor_interval"
        --ez auto_brightness "$auto_brightness"
        --ei diag_port "$diag_port"
    )

    if [[ -n "$yaml_url" ]]; then
        broadcast_args+=(--es yaml_url "$yaml_url")
        info "[CONFIG] yaml_url=${yaml_url} (device will fetch panel config)"
    fi

    adb -s "$serial" shell "${broadcast_args[*]}" 2>&1 \
        | grep -E "result|Broadcast" || true
}

process_device() {
    local ip="$1"
    local serial="${ip}:${ADB_PORT}"

    echo ""
    log "─── $ip ───────────────────────────────────────"

    if ! adb_connect "$ip"; then
        skip "Cannot connect to $serial"
        return
    fi

    # Brief pause for the TCP connection to settle
    sleep 1

    local state
    state=$(adb_state "$ip")
    if [[ "$state" != "device" ]]; then
        skip "$serial state='$state' (expected 'device' — check ADB authorization)"
        return
    fi

    local sdk model
    sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    [[ "$sdk" =~ ^[0-9]+$ ]] || sdk=0
    if (( sdk >= 30 )); then
        info "[DEVICE] ${model:-unknown} (SDK $sdk — Gen2 package list)"
        audit_gen2_packages "$serial"
    else
        info "[DEVICE] ${model:-unknown} (SDK $sdk — Gen1 package list)"
    fi

    remove_unwanted_packages "$serial" "$sdk"
    install_or_update_app    "$serial"
    configure_device         "$serial"
    create_panel_config_if_missing "$ip"
    push_config              "$serial" "$ip"
    ensure_running           "$serial"
    health_check             "$ip"
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
    require_tool adb

    log "NSPanel HA fleet deployment starting"
    log "APK: ${APK_PATH}"
    log "Panels dir: ${PANELS_DIR}"

    local discovered=0 processed=0

    # Explicit-target mode: IPs passed as arguments are deployed to directly,
    # skipping the subnet scan (and the nmap dependency). Use this to limit the
    # blast radius to known panels instead of every host with ADB open.
    if (( $# > 0 )); then
        log "Explicit target mode — $# device(s) given, skipping subnet scan"
        local ip
        for ip in "$@"; do
            (( discovered++ )) || true
            process_device "$ip" || warn "Error processing $ip (continuing)"
            (( processed++ )) || true
        done
        echo ""
        log "Done — discovered $discovered device(s), processed $processed"
        return
    fi

    require_tool nmap
    [[ -f "$SUBNETS_CONF" ]] || { echo "ERROR: Subnets file not found: $SUBNETS_CONF"; exit 1; }

    while IFS= read -r line; do
        # Skip comments and blank lines
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// /}" ]] && continue

        local subnet="$line"
        local ip
        while IFS= read -r ip; do
            [[ -z "$ip" ]] && continue
            (( discovered++ )) || true
            process_device "$ip" || warn "Error processing $ip (continuing)"
            (( processed++ )) || true
        done < <(scan_subnet "$subnet")

    done < "$SUBNETS_CONF"

    echo ""
    log "Done — discovered $discovered device(s), processed $processed"
}

main "$@"
