package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.color.LogProfile
import com.example.color.LogProfileEngine
import com.example.color.LutCategory
import com.example.color.LutPreset
import com.example.ui.CinemaUiState
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaSilver
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorCyan
import com.example.ui.theme.PhosphorGreen
import kotlin.math.roundToInt

@Composable
fun LogProfilesSheet(
    selectedProfile: LogProfile,
    onSelectProfile: (LogProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ObsidianSurface.copy(alpha = 0.96f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("sheet_log_profiles")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Log & HDR Transfer Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaWhite
                )
                Text(
                    text = "OpenGL ES 3.0 real-time OETF pipeline (proprietary curves are simulated approximations)",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaMuted
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("close_log_sheet")) {
                Icon(Icons.Filled.Close, contentDescription = "Close Log Profiles", tint = CinemaSilver)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(LogProfile.entries, key = { it.id }) { profile ->
                val isSelected = profile == selectedProfile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) CinemaAmber.copy(alpha = 0.14f) else ObsidianContainer)
                        .border(
                            1.dp,
                            if (isSelected) CinemaAmber else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectProfile(profile) }
                        .padding(12.dp)
                        .testTag("log_profile_${profile.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MiniLogCurvePreview(profile = profile, selected = isSelected)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) CinemaAmber else CinemaWhite
                            )
                            if (profile.isSimulated) {
                                Text(
                                    text = "SIMULATED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PhosphorCyan,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(PhosphorCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaSilver
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Mid-Grey: ${profile.middleGreyIre.roundToInt()} IRE  •  Latitude: ${profile.targetDynamicRangeStops}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniLogCurvePreview(profile: LogProfile, selected: Boolean) {
    val curve = remember(profile) { LogProfileEngine.sampleCurve(profile, 32) }
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ObsidianVoid)
            .border(1.dp, if (selected) CinemaAmber else Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(5.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val path = Path()
            for (i in curve.indices) {
                val x = (i.toFloat() / (curve.size - 1)) * size.width
                val y = size.height * (1f - curve[i].coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = if (selected) CinemaAmber else PhosphorGreen,
                style = Stroke(width = 2.2f)
            )
        }
    }
}

@Composable
fun LutStudioSheet(
    state: CinemaUiState,
    onSelectLut: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onImportCube: () -> Unit,
    onExportCube: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("ALL") }
    val filteredLuts = remember(state.availableLuts, state.favoriteLutIds, selectedFilter) {
        when (selectedFilter) {
            "ALL" -> state.availableLuts
            "FAV" -> state.availableLuts.filter { it.id in state.favoriteLutIds }
            "HONOR" -> state.availableLuts.filter { it.category == LutCategory.HONOR_STUDIO }
            "FILM" -> state.availableLuts.filter { it.category == LutCategory.CINEMA_FILM }
            "CREATIVE" -> state.availableLuts.filter { it.category == LutCategory.CREATIVE }
            "CUSTOM" -> state.availableLuts.filter { it.category == LutCategory.CUSTOM }
            else -> state.availableLuts
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ObsidianSurface.copy(alpha = 0.96f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("sheet_lut_studio")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "3D LUT Color Studio",
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaWhite
                )
                Text(
                    text = "33³ OpenGL ES 3.0 trilinear 3D textures • Original approximations & .cube I/O",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaMuted
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("close_lut_sheet")) {
                Icon(Icons.Filled.Close, contentDescription = "Close 3D LUT Studio", tint = CinemaSilver)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Intensity slider (0% - 100%)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LUT INTENSITY", style = MaterialTheme.typography.labelSmall, color = CinemaSilver)
            Text(
                "${(state.lutIntensity * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = CinemaAmber
            )
        }
        Slider(
            value = state.lutIntensity,
            onValueChange = onIntensityChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = CinemaAmber,
                activeTrackColor = CinemaAmber,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            ),
            modifier = Modifier.testTag("slider_lut_intensity")
        )

        // Import / Export .cube bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onImportCube,
                modifier = Modifier
                    .weight(1f)
                    .testTag("button_import_cube"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import .cube", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = onExportCube,
                enabled = state.selectedLutId != "none",
                colors = ButtonDefaults.buttonColors(
                    containerColor = CinemaAmber,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("button_export_cube"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export .cube", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Category filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "ALL" to "All (${state.availableLuts.size})",
                "FAV" to "Favorites",
                "HONOR" to "HONOR Studio",
                "FILM" to "Cinema Film",
                "CREATIVE" to "Creative",
                "CUSTOM" to "Custom"
            ).forEach { (key, label) ->
                val active = selectedFilter == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) CinemaAmber else ObsidianElevated)
                        .clickable { selectedFilter = key }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("lut_filter_$key")
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) ObsidianVoid else CinemaWhite
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (filteredLuts.isEmpty()) {
                item {
                    Text(
                        text = "No LUTs in this category yet. Tap Import .cube or star your favorite looks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMuted,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                items(filteredLuts, key = { it.id }) { lut ->
                    LutPresetRow(
                        lut = lut,
                        selected = lut.id == state.selectedLutId,
                        isFavorite = lut.id in state.favoriteLutIds,
                        onSelect = { onSelectLut(lut.id) },
                        onToggleFavorite = { onToggleFavorite(lut.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LutPresetRow(
    lut: LutPreset,
    selected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) CinemaAmber.copy(alpha = 0.15f) else ObsidianContainer)
            .border(
                1.dp,
                if (selected) CinemaAmber else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("lut_item_${lut.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(lut.accentHex))
                .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = lut.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) CinemaAmber else CinemaWhite
                )
                Text(
                    text = lut.category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaMuted
                )
            }
            Text(
                text = lut.description,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaSilver
            )
        }
        if (lut.id != "none") {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("lut_fav_${lut.id}")
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorite ${lut.name}",
                    tint = if (isFavorite) CinemaAmber else CinemaMuted
                )
            }
        }
    }
}
