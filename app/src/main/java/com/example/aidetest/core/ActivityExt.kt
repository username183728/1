package com.example.aidetest

/**
 * Pengganti `this@MainActivity` untuk fungsi ekstensi `MainActivity.xxx()`.
 * Label `this@MainActivity` hanya valid di dalam class MainActivity; di fungsi
 * ekstensi label-nya harus nama fungsi, sehingga dipakai properti ini.
 */
internal val MainActivity.hostActivity: MainActivity get() = this
