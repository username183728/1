package com.example.aidetest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

/** Kode permintaan izin WRITE_EXTERNAL_STORAGE (Android 7–9). */
internal const val LEGACY_STORAGE_PERMISSION_REQUEST = 7311

/** Sekali per proses agar dialog izin tidak muncul berulang-ulang. */
internal object LegacyStorageState {
    @Volatile var asked = false
}

/**
 * OPEN-11: Android 7–9 butuh WRITE_EXTERNAL_STORAGE untuk menulis ke Downloads/Movies/Music/
 * Pictures publik (manifest: maxSdkVersion 28). Panggil di thread UI SEBELUM proses yang
 * menerbitkan hasil.
 *
 * @return true bila akses sudah ada (atau Android 10+). false → izin diminta sekarang;
 *   proses boleh tetap jalan, hasilnya tetap di folder aplikasi dan pengguna diberi tahu.
 */
internal fun MainActivity.ensureLegacyStorageAccess(): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return true
    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true
    if (LegacyStorageState.asked) {
        toast("Izin penyimpanan ditolak: hasil disimpan di folder aplikasi.")
        return false
    }
    LegacyStorageState.asked = true
    toast("Izinkan akses penyimpanan agar hasil masuk Galeri/Downloads.")
    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), LEGACY_STORAGE_PERMISSION_REQUEST)
    return false
}
