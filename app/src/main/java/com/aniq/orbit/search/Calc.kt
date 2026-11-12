package com.aniq.orbit.search

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.pow

/** Tiny calculator: + - * / ^ ( ), × ÷, "x" between numbers, "." decimals, "," thousands. */
object Calc {
    private val timesX = Regex("(?<=[\\d)])\\s*[xX]\\s*(?=[\\d(])")

    fun evaluate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        val e = s.replace(timesX, "*")
            .replace('×', '*').replace('÷', '/').replace('−', '-')
            .replace(",", "") // thousands separators: 1,250*2
            .replace(" ", "")
        // Needs at least one operator after the first character, so "128" or "-5" alone is not maths.
        if (e.drop(1).none { it in "+-*/^" } && !e.contains('(')) return null
        if (e.any { !(it.isDigit() || it in "+-*/^().") }) return null
        val v = try { Parser(e).parse() } catch (_: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    fun format(v: Double): String {
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("trailing")
            return v
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= power() }
                    '/' -> { i++; v /= power() }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') { i++; return base.pow(power()) }
            return base
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> primary()
        }

        private fun primary(): Double {
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("paren")
                i++
                return v
            }
            val start = i
            while (peek()?.let { it.isDigit() || it == '.' } == true) i++
            if (start == i) throw IllegalArgumentException("number")
            return s.substring(start, i).toDouble()
        }
    }
}
