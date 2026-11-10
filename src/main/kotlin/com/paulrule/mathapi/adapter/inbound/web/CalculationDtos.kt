package com.paulrule.mathapi.adapter.inbound.web

import com.paulrule.mathapi.domain.Calculation
import java.math.BigDecimal

/**
 * Nullable so a missing or null field is reported as invalid-request by one check, not by binding. `operation` is a
 * String, not the enum, so an unknown name is reported as unknown-operation. Operands bind from numbers or strings.
 */
data class CalculationRequest(
    val operation: String?,
    val a: BigDecimal?,
    val b: BigDecimal?,
)

/** Every value is a string in plain notation, so clients never lose precision. */
data class CalculationResponse(
    val operation: String,
    val a: String,
    val b: String,
    val result: String,
) {
    companion object {
        fun of(calculation: Calculation) =
            CalculationResponse(
                calculation.operation.name,
                calculation.a.toPlainString(),
                calculation.b.toPlainString(),
                calculation.result().toPlainString(),
            )
    }
}
