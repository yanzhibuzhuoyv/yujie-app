package com.cqmu.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabOrderPolicyTest {
    @Test
    fun `custom order round trips and its first index is the startup destination`() {
        val custom = listOf(4, 0, 5, 1, 3, 2)

        val restored = HomeTabOrderPolicy.decode(HomeTabOrderPolicy.encode(custom))

        assertEquals(custom, restored)
        assertEquals(4, restored.first())
    }

    @Test
    fun `invalid or incomplete order falls back to all six default indexes`() {
        val invalid = listOf(
            null,
            "",
            "not-json",
            """{"v":1,"order":[0,1,2,3,4]}""",
            """{"v":1,"order":[0,1,2,3,4,4]}""",
            """{"v":1,"order":[0,1,2,3,4,6]}""",
            """{"v":2,"order":[0,1,2,3,4,5]}""",
            """{"v":1,"order":[0,1,2,3,4,5]}trailing""",
            """{"v":1,"order":[0,1,2,3,4,5],"extra":true}"""
        )

        invalid.forEach { raw -> assertEquals(HomeTabOrderPolicy.DEFAULT, HomeTabOrderPolicy.decode(raw)) }
    }

    @Test
    fun `move changes only the requested index and respects both boundaries`() {
        val original = listOf(0, 1, 2, 3, 4, 5)

        assertEquals(listOf(0, 2, 1, 3, 4, 5), HomeTabOrderPolicy.move(original, 2, -1))
        assertEquals(listOf(0, 1, 2, 4, 3, 5), HomeTabOrderPolicy.move(original, 3, 1))
        assertEquals(original, HomeTabOrderPolicy.move(original, 0, -1))
        assertEquals(original, HomeTabOrderPolicy.move(original, 5, 1))
    }
}
