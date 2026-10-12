package com.example.aidetest

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object MoneyFormatter {
    /** Rupiah (default aplikasi): "Rp1.500.000" tanpa pecahan, tidak bergantung locale perangkat. */
    fun format(amount: Long): String = FinMoney.rp(amount)

    fun format(amount: Double, locale: Locale = Locale.getDefault(), currencyCode: String? = null): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currencyCode ?: Currency.getInstance(locale).currencyCode) }
        val isIdr = (currencyCode ?: Currency.getInstance(locale).currencyCode).equals("IDR", ignoreCase = true)
        nf.maximumFractionDigits = if (isIdr) 0 else 2
        nf.minimumFractionDigits = 0
        return nf.format(amount)
    }
}
