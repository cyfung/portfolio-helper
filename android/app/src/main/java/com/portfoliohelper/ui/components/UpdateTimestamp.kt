package com.portfoliohelper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.theme.ext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateTimestamp(
    marketData: Map<String, YahooQuote>,
    relatedSymbols: Set<String>,
    modifier: Modifier = Modifier
) {
    val ext = MaterialTheme.ext
    
    // Find the oldest timestamp among related symbols
    val oldestTimestamp = remember(marketData, relatedSymbols) {
        relatedSymbols.mapNotNull { marketData[it]?.timestamp }
            .minOrNull()
    }

    if (oldestTimestamp == null || oldestTimestamp == 0L) return

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(30_000) // Update every 30 seconds
        }
    }

    val diffMinutes = (currentTime - oldestTimestamp) / 60_000
    
    val color = when {
        diffMinutes < 5 -> ext.positive
        diffMinutes < 15 -> ext.warning
        else -> ext.negative
    }

    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(oldestTimestamp))

    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = timeText,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = DataFont
        )
    }
}
