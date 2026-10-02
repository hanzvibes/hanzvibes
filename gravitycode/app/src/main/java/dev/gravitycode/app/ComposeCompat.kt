package dev.gravitycode.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Compatibility no-op for alignment calls outside layout scopes. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.align(alignment: Alignment): Modifier = this
