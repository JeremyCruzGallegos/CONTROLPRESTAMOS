package com.example.controlprestamos.data.google

import java.math.BigDecimal

internal fun decimalMoney(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

/** The original validity and ordinal formulas keep driving the sheet's daily summaries. */
internal fun editedAmountFormula(valid: String, fee: String, base: Long): String =
    "=IF($valid,ROUND(${decimalMoney(base)}+$fee,2),0)"

internal fun editedLateFormula(valid: String, check: String, header: String, storedFee: String, dailyLate: Long, advance: String = ""): String =
    if(advance.isBlank()) "=IF($valid,IF($check=TRUE,ROUND(MAX(0,TODAY()-$header)*${decimalMoney(dailyLate)},2),N($storedFee)),0)"
    else "=IF($valid,IF($check=TRUE,ROUND(MAX(0,MAX(0,TODAY()-$header)*${decimalMoney(dailyLate)}-($advance)),2),N($storedFee)),0)"
