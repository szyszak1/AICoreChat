package org.dylanneve1.aicorechat.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

sealed class DeviceSupportStatus {
    data object Supported : DeviceSupportStatus()
    data object AICoreMissing : DeviceSupportStatus()
    data class NotReady(val reason: String?) : DeviceSupportStatus()
}

fun isAICoreInstalled(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo("com.google.android.aicore", PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo("com.google.android.aicore", 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

suspend fun checkDeviceSupport(context: Context): DeviceSupportStatus = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return@withContext DeviceSupportStatus.NotReady("Android 14 or newer is required")
    }

    if (!isAICoreInstalled(context)) {
        return@withContext DeviceSupportStatus.AICoreMissing
    }

    var model: GenerativeModel? = null
    try {
        model = Generation.getClient()
        when (val status = model.checkStatus()) {
            FeatureStatus.AVAILABLE -> DeviceSupportStatus.Supported
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                var downloadError: String? = null
                model.download().collect { downloadStatus ->
                    if (downloadStatus is DownloadStatus.DownloadFailed) {
                        downloadError = downloadStatus.e.message
                    }
                }
                if (downloadError == null && model.checkStatus() == FeatureStatus.AVAILABLE) {
                    DeviceSupportStatus.Supported
                } else {
                    DeviceSupportStatus.NotReady(downloadError ?: "Downloading Gemini Nano model in progress...")
                }
            }
            FeatureStatus.UNAVAILABLE -> DeviceSupportStatus.NotReady(
                "Gemini Nano Prompt API is unavailable on this device",
            )
            else -> DeviceSupportStatus.NotReady("Unexpected feature status: $status")
        }
    } catch (e: Exception) {
        DeviceSupportStatus.NotReady(e.message)
    } finally {
        try {
            model?.close()
        } catch (_: Exception) {
        }
    }
}
