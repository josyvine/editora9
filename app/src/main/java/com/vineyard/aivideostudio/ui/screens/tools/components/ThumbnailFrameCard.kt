package com.vineyard.aivideostudio.ui.screens.tools.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Theme color constants matching ToolsScreen palette
private val BorderColor = Color(0xFF334155)
private val PurpleAccent = Color(0xFFA855F7)
private val DangerRed = Color(0xFFEF4444)
private val DarkBg = Color(0xFF0C1322)

/**
 * Interactive "+" Slot Button placed directly inside the horizontal filmstrip LazyRow.
 * Triggered to open the photo picker in both Normal Mode and Cue Mode.
 */
@Composable
fun AddThumbnailSlotButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .width(95.dp)
            .height(65.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DarkBg)
            .border(
                BorderStroke(1.5.dp, PurpleAccent),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = PurpleAccent.copy(alpha = 0.2f),
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Thumbnail / Cover",
                        tint = PurpleAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "+ COVER",
                color = PurpleAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Visual card representing the imported Cover / Interstitial Thumbnail
 * rendered alongside extracted video frames in the timeline filmstrip.
 */
@Composable
fun ImportedThumbnailFrameCard(
    bitmap: Bitmap,
    targetFrameIndex: Int,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .width(95.dp)
            .height(65.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(2.dp, PurpleAccent, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        // Thumbnail Image
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Imported Video Cover / Thumbnail",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom Banner with Badge & Placement Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xD9000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "THUMBNAIL",
                color = PurpleAccent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (targetFrameIndex <= 0) "Intro" else "Fr $targetFrameIndex",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Top-Right [X] Delete Button
        Surface(
            shape = CircleShape,
            color = DangerRed,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(16.dp)
                .clickable { onRemove() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove Thumbnail",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}