# nspanelpro-ha

A kiosk launcher that turns a Sonoff NSPanel Pro into a Home Assistant wall
panel. It boots straight into a dashboard, keeps it on screen, wakes on
approach, and reports enough about itself to be managed without a screwdriver.

![A panel showing air-conditioning and lighting controls](docs/images/panel-living-room.png)

The panel above is running the app full-screen; everything on it is a Home
Assistant dashboard. The app's job is to be the thing that never gets in the
way of it.

## Why this exists

The stock launcher gets you a browser. That is not the same as a wall panel:

- **It stays put.** The app registers as `HOME`, so a crash, a reboot or a
  stray tap lands back on the dashboard rather than an Android home screen.
- **It manages its own screen.** Dim on idle, wake on proximity, and a
  brightness that follows the room instead of blinding you at 3am.
- **It can be managed remotely.** Configuration arrives over ADB broadcast or
  MQTT, and every panel serves a diagnostics endpoint, so a fleet does not mean
  a stepladder.
- **It knows what it is.** Two hardware generations report proximity on
  incompatible scales; the app detects which it is on and adapts rather than
  making you configure it.

## Hardware

| Panel | Android | WebView | Notes |
| --- | --- | --- | --- |
| `px30_evb` (80mm) | 8.1 / SDK 27 | Chromium 107 | The original NSPanel Pro |
| `PX30_Android11` (newer 80mm) | 11 / SDK 30 | Chromium 131 | |

Both are 480×480. `minSdk` is 26, so other Android devices will run it — a
tablet reports as `UNKNOWN` and takes conservative defaults rather than
guessing at hardware it cannot identify.

Chromium 107 matters if you write your own cards: it predates `color-mix()`,
and it does not report `prefers-color-scheme: dark`, so those panels cannot
pick up Home Assistant's automatic dark theme. Set a theme per view instead.

<p align="center">
  <img src="docs/images/panel-office.png" width="45%" alt="Climate control and a five-day forecast">
  <img src="docs/images/panel-bedroom.png" width="45%" alt="Bedroom controls with fan speeds">
</p>

## Installing

Download `app-release.apk` from the latest release and:

```sh
adb connect <panel-ip>:5555
adb install -r app-release.apk
```

Then set it as home — the app asks on first launch, or:

```sh
adb shell cmd package set-home-activity pro.nspanel.ha2/.MainActivity
```

Building from source needs a `keystore.properties` beside `settings.gradle.kts`
(see `keystore.properties.example`) and `./gradlew assembleRelease`.

> **Upgrades preserve settings only if the signing key matches.** An APK signed
> with a different key forces an uninstall, and an uninstall wipes the app's
> stored configuration — `ha_url` included, which leaves the panel on a blank
> screen until something pushes it back. Keep the key you started with, or plan
> to re-push config to every panel afterwards.

## Configuring

Configuration comes from three places, later ones winning: built-in defaults, a
YAML file fetched from `yaml_url`, and values pushed over ADB or MQTT.

### Keys

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `ha_url` | string | — | The dashboard to load. The one key a panel cannot do without |
| `yaml_url` | string | — | Fetch the rest of this config from a URL at startup |
| `default_dashboard` | string | — | Path to open when the app starts, if not part of `ha_url` |
| `screen_brightness` | int 0–255 | `180` | 30 is roughly the minimum visible |
| `screen_timeout_seconds` | int | `120` | Seconds of idle before dimming |
| `idle_dim_percent` | int 0–100 | `40` | Dim level as a percentage of `screen_brightness`; 0 is off |
| `auto_brightness` | bool | `true` | Scale brightness with the light sensor |
| `proximity_wake` | bool | `true` | Wake the screen when someone approaches |
| `proximity_threshold` | float | per device | Override the wake distance |
| `proximity_near_high` | bool | per device | Which way the sensor reads; see below |
| `show_status_bar` | bool | `false` | Show the Android status bar |
| `report_interval_seconds` | int | `30` | How often sensor readings are reported |
| `diag_port` | int | `8377` | Port for the diagnostics endpoint; 0 disables |
| `mqtt_broker` | string | — | e.g. `tcp://homeassistant.local:1883` |
| `mqtt_topic` | string | — | Comma-separated; subscribe to several |
| `mqtt_username` | string | — | |
| `mqtt_password` | string | — | Never commit this; see *Secrets* |

`proximity_near_high` exists because the two generations disagree about what
their proximity sensor means: one reports a distance that falls as you
approach, the other reports presence as `1`. The app derives this from the SDK
level and gets it right unattended — the key is an escape hatch for hardware
that reports something neither of them does.

### A minimal config

```yaml
ha_url: "http://homeassistant.local:8123/lovelace/panel"
screen_brightness: 180
screen_timeout_seconds: 120
idle_dim_percent: 40
proximity_wake: true
```

### Pushing config to a running panel

Over ADB, without reinstalling:

```sh
adb shell am broadcast \
  -a pro.nspanel.ha2.PUSH_CONFIG \
  -n pro.nspanel.ha2/.ConfigPushReceiver \
  --es ha_url "http://homeassistant.local:8123/lovelace/panel" \
  --ei screen_brightness 180
```

String values take `--es`, integers `--ei`, booleans `--ez`.

Or over MQTT, if the panel has a broker configured — publish to a topic it
subscribes to. Subscribing to both a per-panel topic and a fleet-wide one lets
you address one panel or all of them:

```yaml
mqtt_topic: "nspanel/living-room/command,nspanel/all/command"
```

### Secrets

`mqtt_password` is the only secret in the config, and it never belongs in a
committed file. `configs/secrets.env` is gitignored; copy
`configs/secrets.env.example` to it and the deploy tooling substitutes the
value at push time.

## Diagnostics

Every panel serves JSON on `diag_port`:

```sh
curl http://<panel-ip>:8377/diag
```

It reports the app version and uptime, the detected hardware generation, screen
state and measured lux, proximity readings, network details, relay state, and
the configuration actually in effect — which is the fastest way to find a panel
that installed cleanly but has no `ha_url`. `GET /healthz` is a bare liveness
check.

The same snapshot is available over ADB when HTTP is not:

```sh
adb shell am broadcast -a pro.nspanel.ha2.DIAG -n pro.nspanel.ha2/.DiagReceiver
```

## Managing more than one

`deploy/` holds tooling for a fleet. `deploy/panels/defaults.yaml` carries
everything common, and `deploy/panels/panel-<ip>.yaml` carries only what
genuinely differs for that panel — usually just its MQTT topic.

That split exists because it was learned the hard way: near-identical copies
drifted apart unnoticed, and stale values sent panels at a host that no longer
existed. A value that is the same everywhere belongs in one place.

Per-panel files matter most after an upgrade that had to uninstall first, since
that wipes stored settings and this is what puts them back.

## Permissions

`WRITE_SETTINGS` sets screen brightness. `SYSTEM_ALERT_WINDOW` keeps the
dashboard above anything else that tries to take the screen.
`RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` are what make it survive a power cut.
There is no analytics and no outbound traffic beyond your Home Assistant
instance and your MQTT broker.

## Licence

Not yet chosen. Until one is added, the usual default applies: all rights
reserved.
