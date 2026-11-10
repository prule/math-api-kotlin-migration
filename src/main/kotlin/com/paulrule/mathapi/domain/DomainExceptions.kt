package com.paulrule.mathapi.domain

/** The requested name is not exactly the name of an operation. */
class UnknownOperationException(
    name: String?,
) : RuntimeException("Unknown operation '$name'. Supported operations: ${Operation.entries.joinToString(", ")}.")

/** An operand, as written, is too precise or its scale is out of bounds. Never carries the value. */
class OperandOutOfRangeException(
    operandName: String,
) : RuntimeException(
        "Operand $operandName must have at most ${Calculation.MAX_PRECISION} significant digits and a scale between " +
            "-${Calculation.MAX_SCALE} and ${Calculation.MAX_SCALE}.",
    )

/** The divisor is zero in some representation. */
class DivisionByZeroException : RuntimeException("Division by zero is undefined.")
