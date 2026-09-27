package com.aisan.radio.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.aisan.radio.ui.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface LocationResult {
    data class Found(val point: GeoPoint) : LocationResult
    data object PermissionDenied : LocationResult
    data object Disabled : LocationResult
    data object Unavailable : LocationResult
}

// 局を近い順に並べるのに使う現在地。精度はおおよその位置情報(数km)で足りる。
object CurrentLocation {
    // これより新しい位置が端末にあれば、測位し直さずにそれを使う
    private const val MAX_AGE_MS = 2 * 60 * 1000L
    // 測位できなかったときに代わりに使う位置の古さの上限(移動した後の古い位置を現在地として出さない)
    private const val MAX_FALLBACK_AGE_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 10 * 1000L

    fun hasPermission(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun get(context: Context): LocationResult {
        if (!hasPermission(context)) return LocationResult.PermissionDenied
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocationResult.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocationResult.Disabled

        val lastKnown = lastKnownLocation(manager)
        val location = if (lastKnown != null && ageMillis(lastKnown) <= MAX_AGE_MS) {
            lastKnown
        } else {
            bestProvider(manager)
                ?.let { provider -> withTimeoutOrNull(TIMEOUT_MS) { requestCurrent(context, manager, provider) } }
                ?: lastKnown?.takeIf { ageMillis(it) <= MAX_FALLBACK_AGE_MS }
        }
        return location?.let { LocationResult.Found(GeoPoint(it.latitude, it.longitude)) } ?: LocationResult.Unavailable
    }

    @SuppressLint("MissingPermission") // 呼び出し元の get() で権限を確認済み
    private fun lastKnownLocation(manager: LocationManager): Location? =
        manager.getProviders(true)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun bestProvider(manager: LocationManager): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // おおよその位置情報の権限だけで GPS を使えるのは Android 12 以降
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.GPS_PROVIDER)
        }
        return candidates.firstOrNull { LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it) }
    }

    @SuppressLint("MissingPermission") // 呼び出し元の get() で権限を確認済み
    private suspend fun requestCurrent(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (e: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (e: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
}
