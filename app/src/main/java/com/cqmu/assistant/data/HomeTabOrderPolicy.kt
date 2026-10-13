package com.cqmu.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object HomeTabOrderPolicy {
    const val TAB_COUNT = 6
    val DEFAULT: List<Int> = (0 until TAB_COUNT).toList()

    fun normalize(order: List<Int>): List<Int> =
        order.takeIf { it.size == TAB_COUNT && it.toSet() == DEFAULT.toSet() } ?: DEFAULT

    fun move(order: List<Int>, tabId: Int, offset: Int): List<Int> {
        val current = normalize(order)
        val from = current.indexOf(tabId)
        if (from < 0) return current
        val to = (from + offset).coerceIn(current.indices)
        if (from == to) return current
        return current.toMutableList().apply {
            this[from] = this[to]
            this[to] = tabId
        }
    }

    fun encode(order: List<Int>): String = JSONObject()
        .put("v", VERSION)
        .put("order", JSONArray(normalize(order)))
        .toString()

    fun decode(raw: String?): List<Int> = runCatching {
        if (raw.isNullOrBlank()) return DEFAULT
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject ?: return DEFAULT
        if (tokener.nextClean() != '\u0000') return DEFAULT
        if (root.keys().asSequence().toSet() != REQUIRED_KEYS) return DEFAULT
        if (root.opt("v") != VERSION) return DEFAULT
        val array = root.optJSONArray("order") ?: return DEFAULT
        if (array.length() != TAB_COUNT) return DEFAULT
        val order = buildList {
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? Number ?: return DEFAULT
                val number = value.toDouble()
                if (!number.isFinite() || number != value.toInt().toDouble()) return DEFAULT
                add(value.toInt())
            }
        }
        normalize(order)
    }.getOrDefault(DEFAULT)

    private const val VERSION = 1
    private val REQUIRED_KEYS = setOf("v", "order")
}
