package com.paulrule.mathapi.domain

import java.math.BigDecimal

data class Calculation(
    val operation: Operation,
    val a: BigDecimal,
    val b: BigDecimal,
) {
    init {
        requireInRange(a, "a")
        requireInRange(b, "b")
    }

    /** The result with trailing zeros stripped; plain notation is rendered by the caller. */
    fun result(): BigDecimal = operation.apply(a, b).stripTrailingZeros()

    companion object {
        const val MAX_PRECISION = 100
        const val MAX_SCALE = 1000

        // The limit reads the operand as written, so it bounds both the arithmetic and the echoed operand.
        private fun requireInRange(
            operand: BigDecimal,
            name: String,
        ) {
            // Two comparisons, not abs(): abs(Int.MIN_VALUE) is negative and would pass.
            if (operand.precision() > MAX_PRECISION || operand.scale() > MAX_SCALE || operand.scale() < -MAX_SCALE) {
                throw OperandOutOfRangeException(name)
            }
        }
    }
}
