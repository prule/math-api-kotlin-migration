package com.paulrule.mathapi.domain

import com.paulrule.mathapi.domain.Operation.ADD
import com.paulrule.mathapi.domain.Operation.SUBTRACT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.math.BigInteger

// The Java rejectsMissingComponents test has no port: non-null Kotlin types make a null component a compile error.
class CalculationTest {
    // Each row: operation, a, b, expected result rendered as plain text.
    @ParameterizedTest(name = "{0} {1} {2} = {3}")
    @CsvSource(
        "ADD, 0.1, 0.2, 0.3",
        "ADD, -1.5, -2.25, -3.75",
        "ADD, -5, 3, -2",
        "ADD, 12345678901234567890.12345678901234567890, 0.00000000000000000001, 12345678901234567890.12345678901234567891",
        "SUBTRACT, 1.5, 1.50, 0",
        "SUBTRACT, 2, 5.5, -3.5",
        "SUBTRACT, -1, -1, 0",
        "MULTIPLY, -7.25, 0, 0",
        "MULTIPLY, 0.00, 123.45, 0",
        "MULTIPLY, -2, -3, 6",
        "MULTIPLY, -2, 3, -6",
        "MULTIPLY, 1.25, -0.004, -0.005",
        "MULTIPLY, 0.001, 0.001, 0.000001",
        "DIVIDE, 2, 3, 0.6666666666666666666666666666666667",
        "DIVIDE, 10000000000000000000000000000000005, 10, 1000000000000000000000000000000000",
        "DIVIDE, 10000000000000000000000000000000015, 10, 1000000000000000000000000000000002",
        "DIVIDE, 1, 4, 0.25",
        "DIVIDE, 1, -8, -0.125",
        "DIVIDE, 100, 0.5, 200",
        "DIVIDE, 0.00, 7, 0",
    )
    fun `performs the operation and normalises the result`(
        operation: Operation,
        a: String,
        b: String,
        expected: String,
    ) {
        val result = Calculation(operation, BigDecimal(a), BigDecimal(b)).result()

        assertThat(result.toPlainString()).isEqualTo(expected)
    }

    @Test
    fun `strips trailing zeros`() {
        val result = Calculation(ADD, BigDecimal("1.50"), BigDecimal("1.50")).result()

        assertThat(result.toPlainString()).isEqualTo("3")
        assertThat(result.scale()).isZero()
    }

    @Test
    fun `keeps its operands as given`() {
        val calculation = Calculation(SUBTRACT, BigDecimal("1.50"), BigDecimal("1E+2"))

        assertThat(calculation.a.toPlainString()).isEqualTo("1.50")
        assertThat(calculation.b.toPlainString()).isEqualTo("100")
    }

    @ParameterizedTest
    @MethodSource("operandsAtTheLimits")
    fun `accepts operands at the limits`(operand: String) {
        val value = BigDecimal(operand)

        assertThatNoException().isThrownBy { Calculation(ADD, value, BigDecimal.ONE) }
        assertThatNoException().isThrownBy { Calculation(ADD, BigDecimal.ONE, value) }
    }

    @ParameterizedTest
    @MethodSource("operandsOnePastTheLimits")
    fun `rejects operands one past the limits`(operand: String) {
        val value = BigDecimal(operand)

        assertThatExceptionOfType(OperandOutOfRangeException::class.java)
            .isThrownBy { Calculation(ADD, value, BigDecimal.ONE) }
            .withMessageStartingWith("Operand a ")
            .withMessageNotContaining(operand)
        assertThatExceptionOfType(OperandOutOfRangeException::class.java)
            .isThrownBy { Calculation(ADD, BigDecimal.ONE, value) }
            .withMessageStartingWith("Operand b ")
            .withMessageNotContaining(operand)
    }

    // abs(Int.MIN_VALUE) is negative, so a scale check written with abs() would let this through.
    @Test
    fun `rejects the most negative scale`() {
        val scaleAtIntMin = BigDecimal(BigInteger.ONE, Int.MIN_VALUE)

        assertThatExceptionOfType(OperandOutOfRangeException::class.java)
            .isThrownBy { Calculation(ADD, scaleAtIntMin, BigDecimal.ONE) }
    }

    @Test
    fun `reports the first out-of-range operand`() {
        val tooBig = BigDecimal("1E+1001")

        assertThatExceptionOfType(OperandOutOfRangeException::class.java)
            .isThrownBy { Calculation(ADD, tooBig, tooBig) }
            .withMessageStartingWith("Operand a ")
    }

    companion object {
        @JvmStatic
        fun operandsAtTheLimits() = listOf("1E+1000", "1E-1000", "-1E+1000", "9".repeat(100))

        @JvmStatic
        fun operandsOnePastTheLimits() = listOf("1E+1001", "1E-1001", "1" + "0".repeat(100), "1e999999999", "0E+1001")
    }
}
