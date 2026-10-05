package ir.abrova.trace.sdk.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.*

/**
 * Utility class to gather device and app information
 */
object DeviceInfo {

    /**
     * Get user agent string for API requests
     */
    fun getUserAgent(context: Context): String {
        val appInfo = getAppInfo(context)
        return buildString {
            append("AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} ")
            append("(Android ${Build.VERSION.RELEASE}; ")
            append("SDK ${Build.VERSION.SDK_INT}; ")
            append("${Build.MANUFACTURER} ${Build.MODEL})")
            if (appInfo != null) {
                append(" ${appInfo.first}/${appInfo.second}")
            }
        }
    }

    /**
     * Get app name and version
     * @return Pair of (appName, versionName) or null if unavailable
     */
    fun getAppInfo(context: Context): Pair<String, String>? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
            val versionName = packageInfo.versionName ?: "unknown"
            Pair(appName, versionName)
        } catch (e: Exception) {
            AbrovaTraceLogger.error("Failed to get app info: ${e.message}")
            null
        }
    }

    /**
     * Get app version name
     */
    fun getAppVersion(context: Context): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get device information as a map
     */
    fun getDeviceInfo(): Map<String, Any?> {
        return mapOf(
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL,
            "device_brand" to Build.BRAND,
            "device_product" to Build.PRODUCT,
            "os_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "device_id" to Build.ID,
            "device_hardware" to Build.HARDWARE,
            "device_board" to Build.BOARD,
            "device_fingerprint" to Build.FINGERPRINT,
            "supported_abis" to getSupportedAbis(),
            "locale" to Locale.getDefault().toString(),
            "timezone" to TimeZone.getDefault().id
        )
    }

    /**
     * Get supported CPU architectures
     */
    private fun getSupportedAbis(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
        }
    }

    /**
     * Get a unique device identifier (hashed for privacy)
     */
    fun getDeviceIdentifier(context: Context): String {
        // Generate a pseudo-unique ID based on device properties
        val deviceInfo = "${Build.MANUFACTURER}|${Build.MODEL}|${Build.SERIAL}|${Build.ID}"
        return deviceInfo.hashCode().toString(16)
    }
}
