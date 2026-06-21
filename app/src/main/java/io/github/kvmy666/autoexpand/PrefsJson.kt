package io.github.kvmy666.autoexpand

import org.json.JSONObject

/**
 * Shared parser for the file-pref IPC payload (/data/local/tmp/tweaks_prefs.json and the
 * base64 Settings.Global mirror). Both the SystemUI/system_server hook (MainHook) and the
 * Gboard hook (KeyboardHook) decode the exact same flat JSON of String→String; this keeps
 * that one loop in a single place instead of three hand-copied versions.
 *
 * Stateless and pure, so it is safe to call from any hooked process.
 */
object PrefsJson {
    /** Flattens a JSON object of string values into a plain map. Never throws. */
    fun parse(text: String): Map<String, String> {
        val json = JSONObject(text)
        val map = HashMap<String, String>(json.length())
        for (key in json.keys()) map[key] = json.getString(key)
        return map
    }
}
