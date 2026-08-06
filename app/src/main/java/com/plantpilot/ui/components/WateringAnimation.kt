package com.plantpilot.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.plantpilot.R

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun WateringOverlay(
    plantName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WateringScene(
                modifier = Modifier
                    .size(340.dp)
                    .background(Color.Black)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Nourishing $plantName",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            val sentences = remember(plantName) {
                listOf(
                    "Dispensing fresh water to keep $plantName healthy & thriving...",
                    "Hydrating roots & replenishing soil moisture...",
                    "Giving $plantName the perfect dose of hydration...",
                    "Soaking up essential moisture for vibrant growth..."
                )
            }
            var currentSentenceIdx by remember { mutableIntStateOf(0) }

            LaunchedEffect(plantName) {
                while (isActive) {
                    delay(3200L)
                    currentSentenceIdx = (currentSentenceIdx + 1) % sentences.size
                }
            }

            AnimatedContent(
                targetState = sentences[currentSentenceIdx],
                transitionSpec = {
                    (fadeIn(tween(600)) + slideInVertically { it / 2 }) togetherWith
                    (fadeOut(tween(600)) + slideOutVertically { -it / 2 })
                },
                label = "watering_sentence"
            ) { sentence ->
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
fun WateringScene(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    val textureView = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var mediaPlayer: MediaPlayer? = null

                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            try {
                                val afd = ctx.resources.openRawResourceFd(R.raw.watering_animation) ?: return
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                    afd.close()
                                    setSurface(Surface(surface))
                                    isLooping = true
                                    setVolume(0f, 0f)

                                    setOnVideoSizeChangedListener { _, vWidth, vHeight ->
                                        if (vWidth > 0 && vHeight > 0) {
                                            adjustAspectRatio(textureView, vWidth, vHeight)
                                        }
                                    }

                                    setOnPreparedListener { mp ->
                                        if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                            adjustAspectRatio(textureView, mp.videoWidth, mp.videoHeight)
                                        }
                                        mp.seekTo(2000)
                                        mp.start()
                                    }
                                    prepareAsync()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            mediaPlayer?.let { mp ->
                                if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                    adjustAspectRatio(textureView, mp.videoWidth, mp.videoHeight)
                                }
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            try {
                                mediaPlayer?.stop()
                                mediaPlayer?.release()
                            } catch (_: Exception) {}
                            mediaPlayer = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun adjustAspectRatio(textureView: TextureView, videoWidth: Int, videoHeight: Int) {
    val viewWidth = textureView.width
    val viewHeight = textureView.height
    if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return

    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

    var scaleX = 1f
    var scaleY = 1f

    // Center Fit (Contain): Fit entire video inside container without cutting edges
    if (videoAspect > viewAspect) {
        scaleY = viewAspect / videoAspect
    } else {
        scaleX = videoAspect / viewAspect
    }

    val matrix = Matrix()
    matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    textureView.setTransform(matrix)
}
