package cn.fairymc.fairyMediaPlayer

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/** Shared FairyBridge palette; dynamic names stay literal even when they contain ampersands. */
internal fun ui(text: String): Component = LegacyComponentSerializer.legacySection()
    .deserialize(text).decoration(TextDecoration.ITALIC, false)
