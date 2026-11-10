package com.paulrule.mathapi.application

import com.paulrule.mathapi.domain.Calculation
import com.paulrule.mathapi.domain.Operation
import java.math.BigDecimal

class PerformCalculation {
    /** @throws com.paulrule.mathapi.domain.UnknownOperationException if no operation has exactly that name */
    fun perform(
        operation: String,
        a: BigDecimal,
        b: BigDecimal,
    ): Calculation = Calculation(Operation.named(operation), a, b)
}
