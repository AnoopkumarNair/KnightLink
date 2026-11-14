package knightlink.core.signals

public class ExpressionException(message: String) : IllegalArgumentException(message)

/**
 * A decoding formula from a definition file, e.g. `(256*A+B)/4` or `(A>>4)&3`.
 *
 * Variables `A`..`Z` are the data bytes (unsigned) in order. Supported: decimal and `0x` hex numbers,
 * `+ - * /`, unary minus, parentheses, and the integer operators `& | << >>`. There are no functions
 * and no way to run code, so definition files from the remote feed are safe to evaluate.
 */
public class Expression private constructor(
    public val source: String,
    private val root: Node,
    /** Number of data bytes the formula reads (highest variable index + 1). */
    public val requiredBytes: Int,
) {
    public fun evaluate(bytes: ByteArray): Double {
        if (bytes.size < requiredBytes) {
            throw ExpressionException("'$source' needs $requiredBytes bytes, got ${bytes.size}")
        }
        return root.eval(bytes)
    }

    override fun toString(): String = source

    public companion object {
        public fun compile(source: String): Expression {
            val parser = Parser(source)
            val root = parser.parse()
            return Expression(source, root, parser.maxIndex + 1)
        }
    }
}

private sealed interface Node {
    fun eval(bytes: ByteArray): Double
}

private class Num(val value: Double) : Node {
    override fun eval(bytes: ByteArray) = value
}

private class ByteVar(val index: Int) : Node {
    override fun eval(bytes: ByteArray) = (bytes[index].toInt() and 0xFF).toDouble()
}

private class Neg(val operand: Node) : Node {
    override fun eval(bytes: ByteArray) = -operand.eval(bytes)
}

private class Bin(val op: Char, val left: Node, val right: Node) : Node {
    override fun eval(bytes: ByteArray): Double {
        val l = left.eval(bytes)
        val r = right.eval(bytes)
        return when (op) {
            '+' -> l + r
            '-' -> l - r
            '*' -> l * r
            '/' -> l / r
            '&' -> (l.toLong() and r.toLong()).toDouble()
            '|' -> (l.toLong() or r.toLong()).toDouble()
            '<' -> (l.toLong() shl r.toInt()).toDouble()
            '>' -> (l.toLong() shr r.toInt()).toDouble()
            else -> error("unreachable operator $op")
        }
    }
}

/** Recursive descent, lowest to highest precedence: `|`, `&`, shifts, `+ -`, `* /`, unary minus. */
private class Parser(private val s: String) {
    private var pos = 0
    var maxIndex = -1
        private set

    fun parse(): Node {
        val node = or()
        skipSpaces()
        if (pos != s.length) fail("unexpected '${s[pos]}'")
        return node
    }

    private fun or(): Node {
        var node = and()
        while (eat("|")) node = Bin('|', node, and())
        return node
    }

    private fun and(): Node {
        var node = shift()
        while (eat("&")) node = Bin('&', node, shift())
        return node
    }

    private fun shift(): Node {
        var node = sum()
        while (true) {
            node = when {
                eat("<<") -> Bin('<', node, sum())
                eat(">>") -> Bin('>', node, sum())
                else -> return node
            }
        }
    }

    private fun sum(): Node {
        var node = product()
        while (true) {
            node = when {
                eat("+") -> Bin('+', node, product())
                eat("-") -> Bin('-', node, product())
                else -> return node
            }
        }
    }

    private fun product(): Node {
        var node = unary()
        while (true) {
            node = when {
                eat("*") -> Bin('*', node, unary())
                eat("/") -> Bin('/', node, unary())
                else -> return node
            }
        }
    }

    private fun unary(): Node = if (eat("-")) Neg(unary()) else primary()

    private fun primary(): Node {
        skipSpaces()
        val c = s.getOrNull(pos) ?: fail("unexpected end")
        return when {
            c == '(' -> {
                pos++
                val inner = or()
                if (!eat(")")) fail("missing ')'")
                inner
            }
            c.isDigit() || c == '.' -> number()
            c in 'A'..'Z' -> {
                pos++
                if (s.getOrNull(pos)?.isLetterOrDigit() == true) fail("variables are single letters A..Z")
                val index = c - 'A'
                if (index > maxIndex) maxIndex = index
                ByteVar(index)
            }
            else -> fail("unexpected '$c'")
        }
    }

    private fun number(): Node {
        val start = pos
        if (s.startsWith("0x", pos) || s.startsWith("0X", pos)) {
            pos += 2
            while (s.getOrNull(pos)?.digitToIntOrNull(16) != null) pos++
            val value = s.substring(start + 2, pos).toLongOrNull(16) ?: fail("bad hex number")
            return Num(value.toDouble())
        }
        while (s.getOrNull(pos)?.let { it.isDigit() || it == '.' } == true) pos++
        val value = s.substring(start, pos).toDoubleOrNull() ?: fail("bad number '${s.substring(start, pos)}'")
        return Num(value)
    }

    private fun eat(token: String): Boolean {
        skipSpaces()
        if (!s.startsWith(token, pos)) return false
        pos += token.length
        return true
    }

    private fun skipSpaces() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun fail(reason: String): Nothing = throw ExpressionException("Formula '$s': $reason at position $pos")
}
