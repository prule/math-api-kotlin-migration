package com.paulrule.mathapi.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class OperationTest {
    @ParameterizedTest
    @EnumSource(Operation::class)
    fun `finds each operation by its exact name`(operation: Operation) {
        assertThat(Operation.named(operation.name)).isSameAs(operation)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["add", "Add", "NOT_AN_OPERATION", "", " ADD"])
    fun `rejects any other name listing the supported ones`(name: String?) {
        assertThatThrownBy { Operation.named(name) }
            .isInstanceOf(UnknownOperationException::class.java)
            .hasMessageStartingWith("Unknown operation '$name'. Supported operations: ")
            .hasMessageEndingWith(".")
    }

    // Parses the message rather than rebuilding it, so separator and order are checked without pinning the names
    // (adding an operation must not edit this test, docs/STANDARDS.md §5).
    @Test
    fun `lists every supported operation in declaration order`() {
        val message = UnknownOperationException("x").message!!
        val list = message.substring(message.indexOf(": ") + 2, message.length - 1)

        assertThat(list.split(", ")).containsExactlyElementsOf(Operation.entries.map { it.name })
    }

    @Test
    fun `adds exactly`() {
        assertThat(Operation.ADD.apply(BigDecimal("0.1"), BigDecimal("0.2"))).isEqualByComparingTo("0.3")
    }

    @Test
    fun `subtracts exactly`() {
        assertThat(Operation.SUBTRACT.apply(BigDecimal("2"), BigDecimal("5.5"))).isEqualByComparingTo("-3.5")
    }

    @Test
    fun `multiplies exactly`() {
        assertThat(Operation.MULTIPLY.apply(BigDecimal("1.25"), BigDecimal("-0.004"))).isEqualByComparingTo("-0.005")
    }

    @Test
    fun `divides to 34 significant digits`() {
        assertThat(Operation.DIVIDE.apply(BigDecimal.ONE, BigDecimal("3"))).isEqualByComparingTo("0.3333333333333333333333333333333333")
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource("1, 0", "1, 0.00", "1, -0", "1, 0E+3", "0, 0")
    fun `rejects division by every zero`(
        a: String,
        zero: String,
    ) {
        assertThatExceptionOfType(DivisionByZeroException::class.java)
            .isThrownBy { Operation.DIVIDE.apply(BigDecimal(a), BigDecimal(zero)) }
    }
}
