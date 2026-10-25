package io.github.kvmy666.autoexpand.hook.notif

import android.os.Build
import android.util.Log

/**
 * Which OEM skin this SystemUI belongs to.
 *
 * Detected once from public build fields plus the OEM version props the skins already publish.
 * It is deliberately advisory: nothing keys a hard failure off it. The resolver uses it to pick
 * class-name candidates and to say which adapter is active in one log line, so a user report
 * starts with "which ROM" answered instead of guessed.
 */
enum class Oem(val label: String) {
    OPLUS("OxygenOS/ColorOS/Realme"),
    XIAOMI("MIUI/HyperOS"),
    SAMSUNG("One UI"),
    VIVO("OriginOS/Funtouch"),
    HONOR("MagicOS"),
    ASUS("ZenUI/ROG"),
    AOSP("AOSP/Pixel"),
    UNKNOWN("unknown"),
}

object OemDetector {

    private const val TAG = "AENotif"

    val current: Oem by lazy {
        val oem = detect()
        Log.i(TAG, "resolver OEM=${oem.name} (${oem.label}) sdk=${Build.VERSION.SDK_INT} " +
            "brand=${Build.BRAND} manuf=${Build.MANUFACTURER} " +
            "props=${versions().joinToString(",") { "${it.first}=${it.second}" }.take(240)}")
        oem
    }

    fun detect(): Oem {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        val props = versions().toMap()

        fun brandHas(vararg names: String) = names.any { brand.contains(it) }
        fun propHas(vararg keys: String) = keys.any { !props[it].isNullOrEmpty() }

        return when {
            brandHas("oneplus", "oplus", "oppo", "realme") ||
                propHas("ro.build.version.oplusrom", "ro.build.version.oplusrom.display",
                        "ro.oplus.version", "ro.build.version.opporom") -> Oem.OPLUS

            brandHas("xiaomi", "redmi", "poco") ||
                propHas("ro.miui.ui.version.name", "ro.mi.os.version.name",
                        "ro.miui.ui.version.code") -> Oem.XIAOMI

            brandHas("samsung") ||
                propHas("ro.build.version.oneui", "ro.build.version.sem") -> Oem.SAMSUNG

            brandHas("vivo", "iqoo") ||
                propHas("ro.vivo.os.version", "ro.vivo.rom.version", "ro.vivo.os.build.display.id") -> Oem.VIVO

            brandHas("honor") ||
                propHas("ro.build.version.magic", "ro.build.version.magicui") -> Oem.HONOR

            brandHas("asus") || propHas("ro.build.asus.version") -> Oem.ASUS

            brandHas("google", "motorola", "sony", "nothing", "fairphone", "nokia", "essential") -> Oem.AOSP

            else -> Oem.UNKNOWN
        }
    }

    /** Reads OEM version props for the log line; unknown values are simply absent. */
    private fun versions(): List<Pair<String, String>> = PROP_KEYS.mapNotNull { key ->
        val value = prop(key)
        if (value.isEmpty()) null else key to value
    }

    private fun prop(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private val PROP_KEYS = listOf(
        "ro.build.version.oplusrom",
        "ro.build.version.oplusrom.display",
        "ro.oplus.version",
        "ro.build.version.opporom",
        "ro.miui.ui.version.name",
        "ro.mi.os.version.name",
        "ro.miui.ui.version.code",
        "ro.build.version.oneui",
        "ro.build.version.sem",
        "ro.vivo.os.version",
        "ro.vivo.rom.version",
        "ro.vivo.os.build.display.id",
        "ro.build.version.magic",
        "ro.build.version.magicui",
        "ro.build.asus.version",
    )
}
