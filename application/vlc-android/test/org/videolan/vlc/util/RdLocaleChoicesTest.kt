package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.RdLocaleChoices.Choice
import java.util.Locale

/**
 * VLC Redesign: every list of languages or countries puts what the device is set to first (its languages in its
 * order, its country), then the others from A to Z, and opens on the value chosen, else on the device's.
 */
class RdLocaleChoicesTest {

    @Test
    fun tagsAreSplitIntoTodaysLanguageAndTheRegion() {
        assertEquals("he" to "IL", RdLocaleChoices.split("iw-IL"))
        assertEquals("he" to "IL", RdLocaleChoices.split("he_IL"))
        assertEquals("pt" to "BR", RdLocaleChoices.split("pt-rBR"))
        assertEquals("id" to "", RdLocaleChoices.split("in"))
        assertEquals("zh" to "TW", RdLocaleChoices.split("zh-Hant-TW"))
        assertEquals("es" to "419", RdLocaleChoices.split("es-419"))
        assertEquals("" to "", RdLocaleChoices.split(""))
    }

    @Test
    fun theCountryIsTheTimeZonesThenTheLanguagesOne() {
        // a phone in Israel set to American English is in Israel
        assertEquals("IL", RdLocaleChoices.deviceCountry("IL", listOf("en-US")))
        // no time zone region (UTC: "001"): the first language that names a country
        assertEquals("FR", RdLocaleChoices.deviceCountry("001", listOf("fr", "fr-FR", "en-US")))
        assertEquals("US", RdLocaleChoices.deviceCountry("", listOf("en-US")))
        assertEquals("", RdLocaleChoices.deviceCountry(null, listOf("en")))
    }

    @Test
    fun aDeviceLanguageMatchesTheSameRegionThenTheLanguageAlone() {
        val values = listOf("", "en", "en-GB", "pt", "pt-BR", "iw", "zh-CN", "zh-TW")
        assertEquals("en-GB", RdLocaleChoices.match(values, "en-GB"))
        assertEquals("en", RdLocaleChoices.match(values, "en-US"))
        assertEquals("pt-BR", RdLocaleChoices.match(values, "pt-BR"))
        assertEquals("pt", RdLocaleChoices.match(values, "pt-PT"))
        // Hebrew is "iw" in some lists and "he" on the device
        assertEquals("iw", RdLocaleChoices.match(values, "he-IL"))
        assertEquals("zh-CN", RdLocaleChoices.match(values, "zh-SG"))
        assertNull(RdLocaleChoices.match(values, "ja-JP"))
        assertNull(RdLocaleChoices.match(values, ""))
    }

    @Test
    fun theDevicesLanguagesComeFirstInItsOrderThenTheOthersByName() {
        val items = listOf("de" to "Deutsch", "he" to "עברית", "en" to "English", "fr" to "Français", "ar" to "العربية")
        val detected = RdLocaleChoices.detectedLanguages(items.map { it.first }, listOf("iw-IL", "en-US", "he"))
        assertEquals(listOf("he", "en"), detected)
        val head = listOf(Choice("", "No language preference"))
        val list = RdLocaleChoices.order(head, items, detected, Locale.ENGLISH)
        assertEquals(listOf("", "he", "en", "de", "fr", "ar"), list.map { it.value })
        assertEquals(listOf(false, true, true, false, false, false), list.map { it.detected })
    }

    @Test
    fun theDevicesCountryComesFirstThenTheOthersInTheInterfacesOrder() {
        val items = listOf("US" to "United States", "IL" to "Israel", "DE" to "Germany", "AT" to "Austria", "OM" to "Oman")
        val detected = RdLocaleChoices.detectedCountries(items.map { it.first }, "il")
        val list = RdLocaleChoices.order(listOf(Choice("", "All")), items, detected, Locale.ENGLISH)
        assertEquals(listOf("", "IL", "AT", "DE", "OM", "US"), list.map { it.value })
        assertTrue(list[1].detected)
        // a country the list doesn't have: none first
        assertEquals(emptyList<String>(), RdLocaleChoices.detectedCountries(items.map { it.first }, "FR"))
    }

    @Test
    fun aValueIsListedOnceAndTheHeadStaysFirst() {
        val items = listOf("en" to "English", "en" to "English (again)", "" to "Device default")
        val list = RdLocaleChoices.order(listOf(Choice("", "Device default")), items, listOf("en", "en"), Locale.ENGLISH)
        assertEquals(listOf("", "en"), list.map { it.value })
        assertEquals("English", list[1].label)
    }

    @Test
    fun aListOpensOnTheValueChosenElseTheDevicesElseTheFirst() {
        val list = listOf(Choice("", "All"), Choice("IL", "Israel", true), Choice("AT", "Austria"), Choice("US", "United States"))
        assertEquals(3, RdLocaleChoices.openAt(list, "US"))
        // nothing chosen: "All" is the value, unless the list prefers the device's (the catalog's filters)
        assertEquals(0, RdLocaleChoices.openAt(list, ""))
        assertEquals(1, RdLocaleChoices.openAt(list, "", preferDetected = true))
        // a value the list no longer has
        assertEquals(1, RdLocaleChoices.openAt(list, "FR"))
        assertEquals(0, RdLocaleChoices.openAt(listOf(Choice("a", "A")), "x"))
    }
}
