package com.music.bitchord.ui.player

import androidx.compose.animation.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.dragVerticalDragDetector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.playback.karaoke.KaraokeMixer
import com.music.bitchord.playback.karaoke.KaraokePreset
import com.music.bitchord.sharedui.resources.stringResource
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.Haptics
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Karaoke capsule: collapsible vertical slider for vocal volume control.
 * Appears in place of the translation button; translation moves under romanization.
 */
@Composable
fun KaraokeCapsule(
    karaokeMixer: KaraokeMixer,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val expanded by remember { mutableStateOf(true) }
    val targetHeight = if (expanded) 180.dp else 48.dp
    val animatedHeight by animateDpAsState(targetHeight, tween(300))

    val vocalPercent = remember { mutableFloatStateOf(100f) }
    val karaokeState = karaokeMixer.state.collectAsStateWithLifecycle()

    // Sync slider with mixer
    kotlinx.coroutines.LaunchedEffect(karaokeMixer) {
        karaokeMixer.state.collect { state ->
            if (state is KaraokeMixer.KaraokeState.Ready) {
                val gainDb = karaokeMixer.vocalGainDb
                val percent = if (gainDb <= -80f) 0f else (10f.pow(gainDb / 20f) * 100f)
                vocalPercent.value = percent.coerceIn(0f, 100f)
            }
        }
    }

    Surface(
        modifier = modifier
            .height(animatedHeight)
            .width(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectDragVerticalDragDetector { change, dragAmount ->
                    if (expanded) {
                        val deltaPercent = (-dragAmount / 180f * 100f).coerceIn(-100f, 100f)
                        val newPercent = (vocalPercent.value + deltaPercent).coerceIn(0f, 100f)
                        vocalPercent.value = newPercent
                        scope.launch(Dispatchers.IO) {
                            karaokeMixer.setVocalPercent(newPercent.roundToInt())
                        }
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Collapsed: just the mic button
            if (!expanded) {
                IconButton(
                    onClick = { expanded = true; haptics.play(Haptic.Select) },
                    modifier = Modifier.padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = stringResource(Res.string.karaoke_enable),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Expanded: slider + presets + close
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Preset chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        KaraokePreset.values().forEach { preset ->
                            val isActive = when {
                                preset == KaraokePreset.Original -> !karaokeMixer.isEnabled
                                preset == KaraokePreset.Sing -> karaokeMixer.isEnabled && karaokeMixer.vocalGainDb <= -8f && karaokeMixer.vocalGainDb >= -12f
                                preset == KaraokePreset.Instrumental -> karaokeMixer.vocalGainDb <= -80f
                                preset == KaraokePreset.VocalOnly -> karaokeMixer.instrumentalGainDb <= -80f
                                else -> false
                            }
                            Text(
                                text = preset.label,
                                fontSize = 10.sp,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            ).let { text ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp)
                                        .clickable {
                                            scope.launch(Dispatchers.IO) { karaokeMixer.setPreset(preset) }
                                            haptics.play(Haptic.Select)
                                        }
                                        .background(
                                            if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                                            RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) { text }
                            }
                        }
                    }

                    // Vertical slider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Slider(
                            value = vocalPercent.value,
                            onValueChange = { newValue ->
                                vocalPercent.value = newValue
                                scope.launch(Dispatchers.IO) {
                                    karaokeMixer.setVocalPercent(newValue.roundToInt())
                                }
                            },
                            valueRange = 0f..100f,
                            steps = 100,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .rotate(-90f)
                                .offset(y = 8.dp), // Center the rotated slider
                        )
                    }

                    // Percent label
                    Text(
                        text = "${vocalPercent.value.roundToInt()}%",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )

                    // Close button
                    IconButton(
                        onClick = {
                            expanded = false
                            onDismiss()
                            haptics.play(Haptic.Select)
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.karaoke_close),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/** Composable preview for development */
@Composable
@Preview
private fun KaraokeCapsulePreview() {
    // Mock mixer for preview
    class MockMixer : KaraokeMixer {
        override var isEnabled: Boolean = true
        override var vocalGainDb: Float = -10f
        override var instrumentalGainDb: Float = 0f
        override val state: kotlinx.coroutines.flow.StateFlow<KaraokeMixer.KaraokeState> =
            kotlinx.coroutines.flow.MutableStateFlow(KaraokeMixer.KaraokeState.Ready(true)).asStateFlow()
        override suspend fun prepare(): Result<Unit> = Result.success(Unit)
        override fun release() {}
        override fun setVocalPercent(percent: Int) {}
        override fun setPreset(preset: KaraokePreset) {}
    }

    KaraokeCapsule(MockMixer(), {})
}