package tech.leonst.bubbles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

val DarkBackground = Color(0xFF121212)
val BubbleBlueLight = Color(0xFF16469E)
val BubbleBlueDark = Color(0xFF0C2860)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DarkScreen()
        }
    }
}


@Composable
fun DarkScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        SmileyBubble(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.43f)
        )
    }
}


@Composable
fun SmileyBubble(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bubbleAnimation")

    val wavePhase1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase1"
    )

    val wavePhase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase2"
    )

    val floatPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floatPhase"
    )

    Canvas(modifier = modifier) {
        val baseScale = min(size.width, size.height)
        val baseRadius = baseScale * 0.46f

        // Gentle floating offset
        val floatX = cos(floatPhase) * baseScale * 0.02f
        val floatY = sin(floatPhase) * baseScale * 0.03f
        val cx = size.width / 2f + floatX
        val cy = size.height / 2f + floatY

        val steps = 120
        val path = Path()
        for (i in 0 until steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            val sideEmphasis = 0.45f + 0.55f * cos(theta) * cos(theta)
            val wave =
                0.048f * sideEmphasis * sin(6f * theta + wavePhase1) +
                        0.028f * sideEmphasis * cos(4f * theta - wavePhase2) +
                        0.018f * sin(3f * theta + wavePhase2)

            val r = baseRadius * (1f + wave)
            val x = cx + r * 1.06f * cos(theta)
            val y = cy + r * 0.96f * sin(theta)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()

        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(BubbleBlueLight, BubbleBlueDark),
                center = Offset(cx - baseRadius * 0.2f, cy - baseRadius * 0.25f),
                radius = baseRadius * 1.35f
            )
        )


        val faceX = cx + floatX * 0.35f
        val faceY = cy + floatY * 0.35f
        val eyeOffsetX = baseRadius * 0.26f
        val eyeY = faceY - baseRadius * 0.06f
        val eyeRadius = baseRadius * 0.075f

        // Left eye
        drawCircle(
            color = Color.White,
            radius = eyeRadius,
            center = Offset(faceX - eyeOffsetX, eyeY)
        )

        // Right eye
        drawCircle(
            color = Color.White,
            radius = eyeRadius,
            center = Offset(faceX + eyeOffsetX, eyeY)
        )

        val mouthWidth = baseRadius * 0.26f
        val mouthHeight = baseRadius * 0.18f
        drawArc(
            color = Color.White,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(faceX - mouthWidth / 2f, faceY + baseRadius * 0.01f),
            size = Size(mouthWidth, mouthHeight),
            style = Stroke(width = baseRadius * 0.055f, cap = StrokeCap.Round)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DarkScreenPreview() {
    DarkScreen()
}