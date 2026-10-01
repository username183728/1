package com.example.aidetest

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object MoneyFormatter {
    fun format(amount: Double, locale: Locale = Locale.getDefault(), currencyCode: String? = null): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currencyCode ?: Currency.getInstance(locale).currencyCode) }
        nf.maximumFractionDigits = 2
        nf.minimumFractionDigits = 0
        return nf.format(amount)
    }
}
