package com.aisan.radio.location

import android.content.Context

const val MESSAGE_LOCATION_PERMISSION_BLOCKED =
    "位置情報の利用が許可されていません。設定アプリでこのアプリに位置情報を許可すると、現在地から近い順に並べられます"

// rationaleBefore/After: 許可を求める直前・拒否された直後の shouldShowRequestPermissionRationale。
// 拒否した後に説明が不要(false)になるのは、もう許可ダイアログを出せない場合だが、Android 11以降は
// ダイアログを閉じただけでも前後とも false になる。そのため、一度明示的に拒否されたことがある場合に限って
// 設定アプリでしか許可できない状態とみなす。
internal fun isPermanentlyDenied(rationaleBefore: Boolean, rationaleAfter: Boolean, deniedOnceBefore: Boolean): Boolean =
    !rationaleAfter && (rationaleBefore || deniedOnceBefore)

// 位置情報の権限が明示的に拒否されたことがあるかの記録(検索画面と放送局一覧で共有する)
class LocationPermissionMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // 許可されたら記録を消す(設定アプリなど、アプリ外で許可された場合も含む)
    fun clear() {
        prefs.edit().remove(KEY_LOCATION_DENIED_ONCE).apply()
    }

    // 拒否されたことを記録し、設定アプリでしか許可できない状態なら true を返す
    fun recordDenial(rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean {
        val deniedOnceBefore = prefs.getBoolean(KEY_LOCATION_DENIED_ONCE, false)
        if (rationaleAfter) prefs.edit().putBoolean(KEY_LOCATION_DENIED_ONCE, true).apply()
        return isPermanentlyDenied(rationaleBefore, rationaleAfter, deniedOnceBefore)
    }

    private companion object {
        const val KEY_LOCATION_DENIED_ONCE = "location_denied_once"
    }
}
