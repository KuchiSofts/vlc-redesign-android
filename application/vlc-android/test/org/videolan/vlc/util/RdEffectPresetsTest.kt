package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** VLC Redesign: the Effects window's presets (RdEffectPresets), the desktop's */
class RdEffectPresetsTest {

    private val presets = listOf(RdEffectPresets.Preset("Night", mapOf("rd_fx_compressor" to true)))

    @Test
    fun names() {
        assertEquals("A long name", RdEffectPresets.normalName("  A\n long\tname "))
        assertEquals(RdEffectPresets.NameError.EMPTY, RdEffectPresets.nameError(presets, "   ", "Default Preset"))
        assertEquals(RdEffectPresets.NameError.DEFAULT, RdEffectPresets.nameError(presets, "default preset", "Default Preset"))
        assertEquals(RdEffectPresets.NameError.TAKEN, RdEffectPresets.nameError(presets, "NIGHT", "Default Preset"))
        // (renamed: its own name is fine)
        assertNull(RdEffectPresets.nameError(presets, "Night", "Default Preset", except = "Night"))
        assertNull(RdEffectPresets.nameError(presets, "Cinema", "Default Preset"))
        assertEquals("Custom Preset 01", RdEffectPresets.freeName(presets) { "Custom Preset $it" })
        assertEquals("Custom Preset 02", RdEffectPresets.freeName(presets + RdEffectPresets.Preset("Custom Preset 01", emptyMap())) { "Custom Preset $it" })
    }

    @Test
    fun onlyTheEffectsAreKept() {
        val all = mapOf("rd_fx_adjust" to true, "rd_fx_adjust-hue" to 30f, "rd_fx_rotate-angle" to "90",
                "rd_fx_presets" to "[]", "rd_fx_preset_chosen" to "Night", "audio_boost" to true, "rd_fx_set" to setOf("a"))
        assertEquals(mapOf("rd_fx_adjust" to true, "rd_fx_adjust-hue" to 30f, "rd_fx_rotate-angle" to "90"), RdEffectPresets.snapshot(all))
    }

    @Test
    fun keptAsJson() {
        val list = listOf(RdEffectPresets.Preset("Cinema", mapOf("rd_fx_adjust" to true, "rd_fx_adjust-gamma" to 1.4f, "rd_fx_transform-type" to "hflip")),
                RdEffectPresets.Preset("Night", mapOf("rd_fx_compressor" to true)))
        val back = RdEffectPresets.parse(RdEffectPresets.serialize(list))
        assertEquals(listOf("Cinema", "Night"), back.map { it.name })
        assertEquals(mapOf("rd_fx_adjust" to true, "rd_fx_adjust-gamma" to 1.4f, "rd_fx_transform-type" to "hflip"), back[0].values)
        // (broken text, a name twice, a setting that is not an effect: left out)
        assertEquals(emptyList<String>(), RdEffectPresets.parse("not json").map { it.name })
        assertEquals(listOf("A"), RdEffectPresets.parse("""[{"name":"A","values":{"x":1,"rd_fx_sepia":true}},{"name":"a"}]""").map { it.name })
        assertEquals(mapOf("rd_fx_sepia" to true), RdEffectPresets.parse("""[{"name":"A","values":{"x":1,"rd_fx_sepia":true}}]""")[0].values)
    }
}
