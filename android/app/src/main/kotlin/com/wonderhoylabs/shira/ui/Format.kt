package com.wonderhoylabs.shira.ui

import java.util.Locale

private const val UNIT_STEP = 1024.0
private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

/** A number of bytes the way people read them, like 1.2 MB. */
fun formatBytes(bytes: Long): String {
	var value = bytes.toDouble()
	var unit = 0
	while (value >= UNIT_STEP && unit < UNITS.lastIndex) {
		value /= UNIT_STEP
		unit++
	}
	return if (unit == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit])
}
