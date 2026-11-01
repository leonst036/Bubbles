package tech.leonst.bubbles

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import android.util.Log

val DarkBackground = Color(0xFF121212)
val BubbleBlueLight = Color(0xFF16469E)
val BubbleBlueDark = Color(0xFF0C2860)
val BubbleActiveLight = Color(0xFF2575FC)
val BubbleActiveDark = Color(0xFF1A3B8B)

class MainActivity : ComponentActivity() {
    private val heartbeatClient = HeartbeatClient()
    private val microphoneManager by lazy { MicrophoneManager(this) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            microphoneManager.start(lifecycleScope)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        heartbeatClient.start(lifecycleScope)

        setContent {
            val isRecording by microphoneManager.isRecording.collectAsState()
            val amplitude by microphoneManager.amplitude.collectAsState()

            MainScreen(
                isRecording = isRecording,
                amplitude = amplitude,
                onBubblePressStart = {
                    if (microphoneManager.hasPermission()) {
                        microphoneManager.start(lifecycleScope)
                        Log.w("microphone", "Microphone is being recorded");

                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        Log.w("microphone", "Microphone stoped recorded");
                    }
                },
                onBubblePressEnd = {
                    microphoneManager.stop()
                },
                onSettingsClick = {
                    startActivity(Intent(this, SettingsScreen::class.java))
                }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        microphoneManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        microphoneManager.stop()
        heartbeatClient.stop()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    amplitude: Float = 0f,
    onBubblePressStart: () -> Unit = {},
    onBubblePressEnd: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }

        SmileyBubble(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.43f),
            isRecording = isRecording,
            amplitude = amplitude,
            onPressStart = onBubblePressStart,
            onPressEnd = onBubblePressEnd
        )
    }
}

@Composable
fun SmileyBubble(
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    amplitude: Float = 0f,
    onPressStart: () -> Unit = {},
    onPressEnd: () -> Unit = {}
) {
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    var isPressed by remember { mutableStateOf(false) }

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

    val lightColor by animateColorAsState(
        targetValue = if (isRecording) BubbleActiveLight else BubbleBlueLight,
        animationSpec = tween(durationMillis = 350),
        label = "lightColor"
    )

    val darkColor by animateColorAsState(
        targetValue = if (isRecording) BubbleActiveDark else BubbleBlueDark,
        animationSpec = tween(durationMillis = 350),
        label = "darkColor"
    )

    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = tween(durationMillis = 100),
        label = "amplitude"
    )

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "pressScale"
    )

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    currentOnPressStart()
                    tryAwaitRelease()
                    isPressed = false
                    currentOnPressEnd()
                }
            )
        }
    ) {
        val baseScale = min(size.width, size.height) * scale
        val baseRadius = baseScale * 0.46f

        // Floating offset
        val floatX = cos(floatPhase) * baseScale * 0.02f
        val floatY = sin(floatPhase) * baseScale * 0.03f
        val cx = size.width / 2f + floatX
        val cy = size.height / 2f + floatY

        val ampBoost = if (isRecording) 1f + animatedAmplitude * 2.5f else 1f

        val steps = 120
        val path = Path()
        for (i in 0 until steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            val sideEmphasis = 0.45f + 0.55f * cos(theta) * cos(theta)
            val wave = (
                    0.048f * sideEmphasis * sin(6f * theta + wavePhase1) +
                            0.028f * sideEmphasis * cos(4f * theta - wavePhase2) +
                            0.018f * sin(3f * theta + wavePhase2)
                    ) * ampBoost

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
                colors = listOf(lightColor, darkColor),
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
        val mouthHeight = baseRadius * (if (isRecording) 0.18f + animatedAmplitude * 0.12f else 0.18f)
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
fun MainScreenPreview() {
    MainScreen()
}
