package com.friday.openwatch

import android.content.Context

object Prefs {
    private const val NAME = "watch_companion_prefs"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_BOUND = "bound_once"

    fun deviceAddress(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_ADDRESS, null)

    fun setDeviceAddress(ctx: Context, address: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICE_ADDRESS, address).apply()
    }

    fun isBoundOnce(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_BOUND, false)

    fun setBoundOnce(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BOUND, true).apply()
    }

    /** Call after a factory reset / unpair on the watch side, so the next connect re-binds. */
    fun clearBoundOnce(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BOUND, false).apply()
    }

    private const val KEY_BATTERY = "battery_level"

    fun batteryLevel(ctx: Context): Int? {
        val v = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_BATTERY, -1)
        return if (v < 0) null else v
    }

    fun setBatteryLevel(ctx: Context, level: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BATTERY, level).apply()
    }
}
