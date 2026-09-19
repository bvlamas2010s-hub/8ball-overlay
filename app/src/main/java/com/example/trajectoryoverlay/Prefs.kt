package com.example.trajectoryoverlay

import android.content.Context

object Prefs {
    private const val NAME = "trajectory_overlay"
    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun showDirect(c: Context) = p(c).getBoolean("direct", true)
    fun showBanks(c: Context) = p(c).getBoolean("banks", true)
    fun showSecondary(c: Context) = p(c).getBoolean("secondary", true)
    fun showAll(c: Context) = p(c).getBoolean("all", true)
    fun sensitivity(c: Context) = p(c).getInt("sensitivity", 18)

    fun setDirect(c: Context, v: Boolean) = p(c).edit().putBoolean("direct", v).apply()
    fun setBanks(c: Context, v: Boolean) = p(c).edit().putBoolean("banks", v).apply()
    fun setSecondary(c: Context, v: Boolean) = p(c).edit().putBoolean("secondary", v).apply()
    fun setAll(c: Context, v: Boolean) = p(c).edit().putBoolean("all", v).apply()
    fun setSensitivity(c: Context, v: Int) = p(c).edit().putInt("sensitivity", v).apply()
}
