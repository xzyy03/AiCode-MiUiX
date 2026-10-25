package com.aicode.feature.terminal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalThemePreset] 预设主题：
 * companion 预设完整性、id 唯一性、ansiColors 长度与「仅按 id 判等」的语义。
 */
class TerminalThemePresetTest {

    private fun theme(id: String, nameRes: Int = 1) = TerminalThemePreset(
        id = id,
        nameRes = nameRes,
        background = 0xFF000000.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
        cursor = 0xFFFF0000.toInt(),
        ansiColors = IntArray(16)
    )

    @Test
    fun all_presets_defined_and_ids_unique() {
        val expected = listOf(
            TerminalThemePreset.FOLLOW_APP,
            TerminalThemePreset.TERMIUS_DARK,
            TerminalThemePreset.GITHUB_LIGHT,
            TerminalThemePreset.DRACULA,
            TerminalThemePreset.ONE_DARK,
            TerminalThemePreset.MONOKAI,
            TerminalThemePreset.GITHUB_DARK
        )
        val presets = TerminalThemePreset.ALL_PRESETS
        assertTrue(presets.containsAll(expected))
        assertEquals(expected.size, presets.size)
        assertEquals(expected.size, presets.map { it.id }.distinct().size)
    }

    @Test
    fun every_preset_has_16_ansi_colors() {
        TerminalThemePreset.ALL_PRESETS.forEach { preset ->
            assertEquals("${preset.id} 的 ANSI 调色板应为 16 色", 16, preset.ansiColors.size)
        }
    }

    @Test
    fun default_ansi_colors_is_16_when_omitted() {
        assertEquals(16, theme("custom").ansiColors.size)
    }

    @Test
    fun every_preset_has_existing_name_resource() {
        TerminalThemePreset.ALL_PRESETS.forEach { preset ->
            assertTrue("${preset.id} 的 nameRes 应为有效资源 id", preset.nameRes != 0)
        }
    }

    @Test
    fun name_resources_are_distinct() {
        val ids = TerminalThemePreset.ALL_PRESETS.map { it.nameRes }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun equals_and_hash_code_are_id_based() {
        val a = theme("custom", nameRes = 1001)
        val b = theme("custom", nameRes = 1002)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(theme("custom"), theme("other"))
    }

    @Test
    fun find_by_id_returns_matching_preset() {
        assertEquals(TerminalThemePreset.FOLLOW_APP, TerminalThemePreset.findById("follow_app"))
        assertEquals(TerminalThemePreset.TERMIUS_DARK, TerminalThemePreset.findById("termius_dark"))
        assertEquals(TerminalThemePreset.GITHUB_LIGHT, TerminalThemePreset.findById("github_light"))
        assertEquals(TerminalThemePreset.DRACULA, TerminalThemePreset.findById("dracula"))
        assertEquals(TerminalThemePreset.ONE_DARK, TerminalThemePreset.findById("one_dark"))
        assertEquals(TerminalThemePreset.MONOKAI, TerminalThemePreset.findById("monokai"))
        assertEquals(TerminalThemePreset.GITHUB_DARK, TerminalThemePreset.findById("github_dark"))
    }

    @Test
    fun find_by_id_unknown_falls_back_to_follow_app() {
        assertEquals(TerminalThemePreset.FOLLOW_APP, TerminalThemePreset.findById("no_such_theme"))
    }

    @Test
    fun resolve_follow_app_uses_app_colors_and_mode_ansi() {
        val lightResolved = TerminalThemePreset.resolve(
            preset = TerminalThemePreset.FOLLOW_APP,
            isDark = false,
            backgroundArgb = 0xFFF5F3EC.toInt(),
            foregroundArgb = 0xFF141413.toInt(),
            cursorArgb = 0xFFC15F3C.toInt()
        )
        assertEquals(0xFFF5F3EC.toInt(), lightResolved.background)
        assertEquals(0xFF141413.toInt(), lightResolved.foreground)
        assertEquals(0xFFC15F3C.toInt(), lightResolved.cursor)
        assertTrue(lightResolved.ansiColors.contentEquals(TerminalThemePreset.GITHUB_LIGHT.ansiColors))

        val darkResolved = TerminalThemePreset.resolve(
            preset = TerminalThemePreset.FOLLOW_APP,
            isDark = true,
            backgroundArgb = 0xFF1A1917.toInt(),
            foregroundArgb = 0xFFFAF9F5.toInt(),
            cursorArgb = 0xFFE08A6B.toInt()
        )
        assertEquals(0xFF1A1917.toInt(), darkResolved.background)
        assertEquals(0xFFFAF9F5.toInt(), darkResolved.foreground)
        assertEquals(0xFFE08A6B.toInt(), darkResolved.cursor)
        assertTrue(darkResolved.ansiColors.contentEquals(TerminalThemePreset.TERMIUS_DARK.ansiColors))
        assertNotEquals(lightResolved, darkResolved)
    }

    @Test
    fun resolve_fixed_preset_returns_preset_unchanged() {
        val resolved = TerminalThemePreset.resolve(
            preset = TerminalThemePreset.DRACULA,
            isDark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
            foregroundArgb = 0xFF000000.toInt(),
            cursorArgb = 0xFFFF0000.toInt()
        )
        assertEquals(TerminalThemePreset.DRACULA, resolved)
    }
}