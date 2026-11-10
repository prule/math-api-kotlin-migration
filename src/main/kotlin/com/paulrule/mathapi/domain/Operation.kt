package com.paulrule.mathapi.domain

import java.math.BigDecimal
import java.math.MathContext

/** The closed set of operations. Each is exact unless its rule says otherwise. */
enum class Operation {
    ADD {
        override fun apply(
            a: BigDecimal,
            b: BigDecimal,
        ): BigDecimal = a.add(b)
    },
    SUBTRACT {
        override fun apply(
            a: BigDecimal,
            b: BigDecimal,
        ): BigDecimal = a.subtract(b)
    },
    MULTIPLY {
        override fun apply(
            a: BigDecimal,
            b: BigDecimal,
        ): BigDecimal = a.multiply(b)
    },
    DIVIDE {
        override fun apply(
            a: BigDecimal,
            b: BigDecimal,
        ): BigDecimal {
            if (b.signum() == 0) throw DivisionByZeroException() // every zero representation
            return a.divide(b, MathContext.DECIMAL128)
        }
    },
    ;

    abstract fun apply(
        a: BigDecimal,
        b: BigDecimal,
    ): BigDecimal

    companion object {
        /** Exact, case-sensitive lookup; `valueOf` would leak a platform message for unknown or null names. */
        fun named(name: String?): Operation = entries.firstOrNull { it.name == name } ?: throw UnknownOperationException(name)
    }
}
