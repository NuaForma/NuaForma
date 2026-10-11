package dev.nuaforma.contracts

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

internal object StructuredValues {
    private const val MAX_DEPTH = 64
    private const val MAX_VALUES = 100_000

    fun objectCopy(values: Map<String, *>): Map<String, Any?> {
        val snapshot = Copier().copy(values, 0)
        @Suppress("UNCHECKED_CAST")
        return snapshot as Map<String, Any?>
    }

    fun valueCopy(value: Any?): Any? = Copier().copy(value, 0)

    private class Copier {
        private val ancestors = IdentityHashMap<Any, Boolean>()
        private var count = 0

        fun copy(value: Any?, depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "Structured values exceed the maximum nesting depth" }
            require(++count <= MAX_VALUES) { "Structured values exceed the maximum value count" }
            return when (value) {
                null, is String, is Boolean, is Byte, is Short, is Int, is Long,
                is BigInteger, is BigDecimal -> value
                is Double -> value.also { require(it.isFinite()) { "Numbers must be finite" } }
                is Float -> value.also { require(it.isFinite()) { "Numbers must be finite" } }
                is Map<*, *> -> within(value) {
                    val result = LinkedHashMap<String, Any?>()
                    value.forEach { (key, item) ->
                        require(key is String) { "Object keys must be strings" }
                        result[key] = copy(item, depth + 1)
                    }
                    Collections.unmodifiableMap(result)
                }
                is List<*> -> within(value) {
                    Collections.unmodifiableList(value.map { copy(it, depth + 1) })
                }
                else -> throw IllegalArgumentException("Values must be JSON-compatible, not arbitrary objects")
            }
        }

        private fun <T> within(value: Any, action: () -> T): T {
            require(ancestors.put(value, true) == null) { "Structured values must not contain cycles" }
            return try {
                action()
            } finally {
                ancestors.remove(value)
            }
        }
    }
}

internal fun <T : Any> immutableList(values: Collection<T>): List<T> {
    require((values as Collection<*>).none { it == null }) { "Collection elements must not be null" }
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <T : Any> immutableSet(values: Collection<T>): Set<T> {
    require((values as Collection<*>).none { it == null }) { "Collection elements must not be null" }
    return Collections.unmodifiableSet(LinkedHashSet(values))
}
