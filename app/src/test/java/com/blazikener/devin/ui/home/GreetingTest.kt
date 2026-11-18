package com.blazikener.devin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun greetingUsesTimeOfDayAndFirstName() {
        val cases = listOf(
            4 to "Good evening",
            5 to "Good morning",
            11 to "Good morning",
            12 to "Good afternoon",
            16 to "Good afternoon",
            17 to "Good evening",
            21 to "Good evening",
            23 to "Good evening",
        )
        cases.forEach { (hour, expected) ->
            assertEquals(expected, greeting(hour, null))
            assertEquals(expected, greeting(hour, ""))
            assertEquals(expected, greeting(hour, "  "))
            assertEquals("$expected, Yasir", greeting(hour, "Yasir Ahmed"))
        }
    }
}
