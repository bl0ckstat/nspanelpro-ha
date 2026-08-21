package pro.nspanel.ha2.panel

import pro.nspanel.ha2.data.PanelConfig

/**
 * Best-effort regex parser for the NSPanel YAML config format. Handles both flat and
 * indented/nested keys (indentation is matched by the leading `\s*`). Unknown keys and
 * sections are silently ignored. Missing keys fall back to [PanelConfig.DEFAULT].
 */
object PanelYamlParser {

    fun parse(raw: String?): PanelConfig {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return PanelConfig.DEFAULT

        // Strip comment-only lines and inline comments so they don't pollute value captures.
        val stripped = text.lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n") { line ->
                val commentIdx = line.indexOf(" #")
                if (commentIdx >= 0) line.substring(0, commentIdx) else line
            }

        var c = PanelConfig.DEFAULT

        intKey("screen_brightness").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(screenBrightness = it.coerceIn(0, 255))
        }
        intKey("screen_timeout_seconds").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(screenTimeoutSeconds = it.coerceAtLeast(0))
        }
        boolKey("proximity_wake").find(stripped)?.let { m ->
            c = c.copy(proximityWake = m.groupValues[1].equals("true", ignoreCase = true))
        }
        numKey("lux_dark").find(stripped)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            c = c.copy(luxDark = it.coerceAtLeast(0f))
        }
        numKey("lux_bright").find(stripped)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            c = c.copy(luxBright = it.coerceAtLeast(1f))
        }
        numKey("proximity_threshold").find(stripped)?.groupValues?.get(1)?.toFloatOrNull()?.let {
            c = c.copy(proximityThreshold = it.coerceAtLeast(0f))
        }
        boolKey("proximity_near_high").find(stripped)?.let { m ->
            c = c.copy(proximityNearHigh = m.groupValues[1].equals("true", ignoreCase = true))
        }
        stringKey("default_dashboard").find(stripped)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) c = c.copy(defaultDashboard = v)
        }
        intKey("idle_dim_percent").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(idleDimPercent = it.coerceIn(0, 100))
        }
        boolKey("show_status_bar").find(stripped)?.let { m ->
            c = c.copy(showStatusBar = m.groupValues[1].equals("true", ignoreCase = true))
        }
        intKey("report_interval_seconds").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(sensorReportIntervalSeconds = it.coerceAtLeast(1))
        }
        boolKey("auto_brightness").find(stripped)?.let { m ->
            c = c.copy(autoBrightness = m.groupValues[1].equals("true", ignoreCase = true))
        }
        intKey("diag_port").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(diagPort = it.coerceIn(0, 65535))
        }
        stringKey("mqtt_broker").find(stripped)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) c = c.copy(mqttBroker = v)
        }
        stringKey("mqtt_topic").find(stripped)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) c = c.copy(mqttTopic = v)
        }
        stringKey("mqtt_username").find(stripped)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) c = c.copy(mqttUsername = v)
        }
        stringKey("syslog_host").find(stripped)?.let { m ->
            c = c.copy(syslogHost = m.groupValues[1].trim().trim('"', '\''))
        }
        intKey("syslog_port").find(stripped)?.groupValues?.get(1)?.toIntOrNull()?.let {
            c = c.copy(syslogPort = it.coerceIn(1, 65535))
        }
        stringKey("mqtt_password").find(stripped)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) c = c.copy(mqttPassword = v)
        }

        return c
    }

    private fun intKey(key: String) =
        Regex("""^\s*$key:\s*(\d+)\s*$""", RegexOption.MULTILINE)

    /** Like [intKey] but accepts a decimal: proximity readings are floats. */
    private fun numKey(key: String) =
        Regex("""^\s*$key:\s*(\d+(?:\.\d+)?)\s*$""", RegexOption.MULTILINE)

    private fun boolKey(key: String) =
        Regex("""^\s*$key:\s*(true|false)\s*$""", RegexOption.MULTILINE)

    private fun stringKey(key: String) =
        Regex("""^\s*$key:\s*(.+?)\s*$""", RegexOption.MULTILINE)
}
