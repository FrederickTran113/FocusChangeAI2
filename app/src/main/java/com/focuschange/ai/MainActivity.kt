
package com.focuschange.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.focuschange.ai.ui.theme.FocusChangeAITheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var previewView: PreviewView? = null
    private var cameraStarted = false
    private var lastVideoTimestamp = 0L

    // Thông tin giao diện
    private var status by mutableStateOf("Đang khởi tạo MediaPipe...")
    private var faceCount by mutableIntStateOf(0)
    private var blinkCount by mutableIntStateOf(0)

    private var landmarkPoints by mutableStateOf(
        emptyList<Pair<Float, Float>>()
    )

    private var ear1Text by mutableStateOf("--")
    private var ear2Text by mutableStateOf("--")
    private var eyeStatus by mutableStateOf("Chưa xác định")
    private var processingText by mutableStateOf("-- ms")

    // Ngưỡng thử nghiệm theo EAR mắt mở đã quan sát.
    private val eyeCloseThreshold = 0.14f
    private val eyeOpenThreshold = 0.23f
    private val minClosedFrames = 1

    private var eyesClosed = false
    private var closedFrameCount = 0

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                previewView?.let { startCamera(it) }
            } else {
                status = "Bạn cần cấp quyền camera."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            faceLandmarker =
                FaceLandmarkerHelper(this).setupFaceLandmarker()
            status = "MediaPipe đã sẵn sàng."
        } catch (e: Exception) {
            Log.e("FocusChangeAI", "Lỗi khởi tạo MediaPipe", e)
            status = "Lỗi model: ${e.message}"
        }

        setContent {
            FocusChangeAITheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "FocusChangeAI",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(text = status)

                        Text(
                            text = "Số khuôn mặt: $faceCount",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(text = "Số điểm mốc: ${landmarkPoints.size}")

                        Text(
                            text = "Số lần chớp mắt: $blinkCount",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(text = "EAR mắt 1: $ear1Text")
                        Text(text = "EAR mắt 2: $ear2Text")
                        Text(text = "Trạng thái mắt: $eyeStatus")
                        Text(text = "Thời gian nhận diện: $processingText")

                        Spacer(Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    PreviewView(context).also { view ->
                                        view.scaleType =
                                            PreviewView.ScaleType.FILL_CENTER
                                        previewView = view

                                        if (faceLandmarker == null) {
                                            status = "Model chưa sẵn sàng."
                                        } else {
                                            checkCameraPermission(view)
                                        }
                                    }
                                }
                            )

                            Canvas(Modifier.fillMaxSize()) {
                                val eyeIndices = setOf(
                                    33, 160, 158, 133, 153, 144,
                                    362, 385, 387, 263, 373, 380
                                )

                                landmarkPoints.forEachIndexed { index, point ->
                                    drawCircle(
                                        color = if (index in eyeIndices) {
                                            Color.Yellow
                                        } else {
                                            Color.Green
                                        },
                                        radius = if (index in eyeIndices) {
                                            4f
                                        } else {
                                            2f
                                        },
                                        center = Offset(
                                            (1f - point.first) * size.width,
                                            point.second * size.height
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission(view: PreviewView) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCamera(view)
        } else {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun startCamera(view: PreviewView) {
        if (cameraStarted || faceLandmarker == null) return

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                // Giảm độ phân giải phân tích để giảm thời gian xử lý.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )

                cameraStarted = true

                runOnUiThread {
                    status = "Camera trước đang hoạt động."
                }
            } catch (e: Exception) {
                Log.e("FocusChangeAI", "Lỗi mở camera", e)

                runOnUiThread {
                    status = "Lỗi camera: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun distance(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateEAR(
        points: List<Pair<Float, Float>>,
        indices: List<Int>
    ): Float {
        if (
            indices.size != 6 ||
            indices.any { it !in points.indices }
        ) {
            return 1f
        }

        val p1 = points[indices[0]]
        val p2 = points[indices[1]]
        val p3 = points[indices[2]]
        val p4 = points[indices[3]]
        val p5 = points[indices[4]]
        val p6 = points[indices[5]]

        val vertical1 = distance(p2, p6)
        val vertical2 = distance(p3, p5)
        val horizontal = distance(p1, p4)

        if (horizontal < 0.000001f) return 1f

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null

        try {
            val landmarker = faceLandmarker ?: return

            // Chuyển YUV trực tiếp sang Bitmap RGB, không nén JPEG.
            bitmap = imageProxyToBitmap(imageProxy)

            val rotation = imageProxy.imageInfo.rotationDegrees

            rotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply {
                    postRotate(rotation.toFloat())
                }

                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    false
                )
            } else {
                bitmap
            }

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            val timestamp = maxOf(
                SystemClock.uptimeMillis(),
                lastVideoTimestamp + 1
            )
            lastVideoTimestamp = timestamp

            val startNs = SystemClock.elapsedRealtimeNanos()

            val result = landmarker.detectForVideo(
                mpImage,
                timestamp
            )

            val processingMs =
                (SystemClock.elapsedRealtimeNanos() - startNs) /
                        1_000_000f

            val landmarks = result.faceLandmarks().firstOrNull()

            val points = landmarks?.map { point ->
                point.x() to point.y()
            } ?: emptyList()

            var blinkDetected = false
            var ear1: Float? = null
            var ear2: Float? = null
            var currentEyeStatus = "Không phát hiện khuôn mặt"

            if (points.isNotEmpty()) {
                ear1 = calculateEAR(
                    points,
                    listOf(33, 160, 158, 133, 153, 144)
                )

                ear2 = calculateEAR(
                    points,
                    listOf(362, 385, 387, 263, 373, 380)
                )

                val e1 = ear1
                val e2 = ear2

                // Chỉ xác nhận nhắm khi cả hai mắt đều thấp.
                val bothEyesClosed =
                    e1 < eyeCloseThreshold &&
                            e2 < eyeCloseThreshold

                // Xác nhận mở lại khi cả hai mắt vượt ngưỡng mở.
                val bothEyesOpen =
                    e1 > eyeOpenThreshold &&
                            e2 > eyeOpenThreshold

                if (bothEyesClosed) {
                    closedFrameCount++

                    if (closedFrameCount >= minClosedFrames) {
                        eyesClosed = true
                    }
                } else if (bothEyesOpen) {
                    if (eyesClosed) {
                        blinkDetected = true
                    }

                    eyesClosed = false
                    closedFrameCount = 0
                }

                currentEyeStatus = if (eyesClosed) {
                    "Đang nhắm"
                } else {
                    "Đang mở hoặc chuyển trạng thái"
                }

                Log.d(
                    "BlinkDebug",
                    "EAR1=$e1, EAR2=$e2, closed=$eyesClosed"
                )
            } else {
                eyesClosed = false
                closedFrameCount = 0
            }

            Log.d(
                "FaceSpeed",
                "MediaPipe: ${"%.1f".format(Locale.US, processingMs)} ms"
            )

            runOnUiThread {
                faceCount = if (landmarks != null) 1 else 0
                landmarkPoints = points

                if (blinkDetected) {
                    blinkCount++
                }

                ear1Text = ear1?.let {
                    String.format(Locale.US, "%.3f", it)
                } ?: "--"

                ear2Text = ear2?.let {
                    String.format(Locale.US, "%.3f", it)
                } ?: "--"

                eyeStatus = currentEyeStatus
                processingText = String.format(
                    Locale.US,
                    "%.1f ms",
                    processingMs
                )

                status = if (landmarks != null) {
                    "Đã phát hiện khuôn mặt."
                } else {
                    "Chưa phát hiện khuôn mặt."
                }
            }
        } catch (e: Exception) {
            Log.e("FocusChangeAI", "Lỗi phân tích ảnh", e)

            runOnUiThread {
                status = "Lỗi nhận diện: ${e.message}"
                landmarkPoints = emptyList()
                faceCount = 0
                eyeStatus = "Không xác định"
            }
        } finally {
            if (rotatedBitmap != null && rotatedBitmap !== bitmap) {
                rotatedBitmap.recycle()
            }

            bitmap?.recycle()
            imageProxy.close()
        }
    }

    // Đọc YUV_420_888 có xét rowStride và pixelStride.
    // Không tạo JPEG trung gian.
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val yRow = yBase + row * yPlane.rowStride
            val uvRow = row / 2

            for (col in 0 until width) {
                val yIndex = yRow + col * yPlane.pixelStride

                val uvCol = col / 2
                val uIndex =
                    uBase + uvRow * uPlane.rowStride +
                            uvCol * uPlane.pixelStride
                val vIndex =
                    vBase + uvRow * vPlane.rowStride +
                            uvCol * vPlane.pixelStride

                val y = yBuffer.get(yIndex).toInt() and 0xFF
                val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                val y1192 = 1192 * (y - 16).coerceAtLeast(0)

                val r = ((y1192 + 1634 * v).coerceIn(0, 262143) shr 10)
                val g = ((y1192 - 833 * v - 400 * u)
                    .coerceIn(0, 262143) shr 10)
                val b = ((y1192 + 2066 * u).coerceIn(0, 262143) shr 10)

                pixels[row * width + col] =
                    (0xFF shl 24) or
                            (r shl 16) or
                            (g shl 8) or
                            b
            }
        }

        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    override fun onDestroy() {
        previewView = null

        faceLandmarker?.close()
        faceLandmarker = null

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }

        super.onDestroy()
    }
}
