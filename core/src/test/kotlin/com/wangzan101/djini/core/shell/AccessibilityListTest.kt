package com.wangzan101.djini.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val djini = PermissionCommands.accessibilityService("com.wangzan101.djini")
    private val mikaLong = "com.wangzan101.djini/com.wangzan101.djini.a11y.DjiniAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, djini))
            assertEquals(djini, AccessibilityList.with(raw, djini))
            assertEquals("", AccessibilityList.without(raw, djini))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$djini", AccessibilityList.with(other, djini))
        assertEquals("$other:$other2:$djini", AccessibilityList.with("$other:$other2\n", djini))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$djini", AccessibilityList.with("$other:$djini", djini))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$mikaLong:$other", AccessibilityList.with("$mikaLong:$other", djini))
        assertTrue(AccessibilityList.contains(mikaLong, djini))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$djini:$other2", djini))
        assertEquals(other, AccessibilityList.without("$mikaLong:$other", djini))
        assertEquals(other, AccessibilityList.without(other, djini))
        assertEquals("", AccessibilityList.without(djini, djini))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$djini", AccessibilityList.with(":$other:: $other2 :", djini))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$djini:$other2:", djini))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.wangzan101.djini/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, djini))
        assertEquals(sibling, AccessibilityList.without("$sibling:$djini", djini))
    }
}
