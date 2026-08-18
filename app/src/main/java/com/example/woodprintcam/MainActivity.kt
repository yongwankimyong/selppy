package com.example.woodprintcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.print.PrintHelper
import com.example.woodprintcam.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 사용 가능한 프레임 종류
enum class FrameType {
    NONE, WOOD, POLAROID, HEART, FILMSTRIP, STARS
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastCapturedBitmap: Bitmap? = null   // 프레임까지 적용된, 실제 인쇄될 이미지
    private var originalBitmap: Bitmap? = null        // 프레임 적용 전 원본(필터만 적용된) 이미지
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedFrame = FrameType.NONE

    // 카메라 권한 요청 런처.
    // 사용자가 한 번 "허용"을 누르면 안드로이드 시스템이 앱 재실행 시에도 계속 허용 상태를
    // 자동으로 유지해줍니다. 따라서 매번 요청할 필요 없이, 시작할 때 이미 허용됐는지만
    // 확인(hasCameraPermission)하고, 허용 안 됐을 때만 이 런처를 호출합니다.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraUi()
                startCamera()
            } else {
                showPermissionDeniedUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) {
            showCameraUi()
            startCamera()
        } else {
            // 최초 1회만 요청. 이후에는 hasCameraPermission()이 true를 반환하므로
            // 이 분기를 다시 타지 않습니다.
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.shutterButton.setOnClickListener { takePhoto() }
        binding.retakeButton.setOnClickListener { returnToCameraMode() }
        binding.printButton.setOnClickListener { printCapturedPhoto() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }

        binding.frameNoneBtn.setOnClickListener { selectFrame(FrameType.NONE) }
        binding.frameWoodBtn.setOnClickListener { selectFrame(FrameType.WOOD) }
        binding.framePolaroidBtn.setOnClickListener { selectFrame(FrameType.POLAROID) }
        binding.frameHeartBtn.setOnClickListener { selectFrame(FrameType.HEART) }
        binding.frameFilmBtn.setOnClickListener { selectFrame(FrameType.FILMSTRIP) }
        binding.frameStarBtn.setOnClickListener { selectFrame(FrameType.STARS) }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showCameraUi() {
        binding.permissionText.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
    }

    private fun showPermissionDeniedUi() {
        binding.permissionText.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        Toast.makeText(this, R.string.permission_denied_msg, Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 전면 <-> 후면 카메라 전환
    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = applyBrightFilter(imageProxyToBitmap(image))
                    image.close()
                    runOnUiThread { showCapturedPhoto(bitmap) }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "촬영 실패: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    // ImageProxy(YUV/JPEG) -> Bitmap 변환 + 회전 보정 + 전면 카메라 좌우 반전 보정
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = Matrix()
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        // 전면 카메라는 화면에 거울처럼 보이므로, 저장되는 사진도
        // 화면에서 본 것과 같게 좌우를 뒤집어줍니다.
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f)
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    // 밝고 화사한 느낌의 필터 (밝기 업 + 채도 업 + 살짝 선명하게)
    private fun applyBrightFilter(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val brightness = 18f   // 밝기 (0~255 범위에서 더할 값)
        val contrast = 1.08f   // 대비 (1.0이 원본)
        val saturation = 1.35f // 채도 (1.0이 원본, 높을수록 화사함)

        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val brightnessContrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val finalMatrix = ColorMatrix().apply {
            postConcat(saturationMatrix)
            postConcat(brightnessContrastMatrix)
        }

        paint.colorFilter = ColorMatrixColorFilter(finalMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun showCapturedPhoto(bitmap: Bitmap) {
        originalBitmap = bitmap
        selectedFrame = FrameType.NONE
        updateFramedPreview()

        binding.capturedImageView.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        binding.shutterButton.visibility = android.view.View.GONE
        binding.resultButtonRow.visibility = android.view.View.VISIBLE
        binding.frameSelectorScroll.visibility = android.view.View.VISIBLE
    }

    private fun returnToCameraMode() {
        lastCapturedBitmap = null
        originalBitmap = null
        binding.capturedImageView.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.shutterButton.visibility = android.view.View.VISIBLE
        binding.resultButtonRow.visibility = android.view.View.GONE
        binding.frameSelectorScroll.visibility = android.view.View.GONE
    }

    // 프레임 선택 시 호출 - 원본에 새로 선택한 프레임을 적용해서 미리보기 갱신
    private fun selectFrame(frame: FrameType) {
        selectedFrame = frame
        updateFramedPreview()
    }

    private fun updateFramedPreview() {
        val base = originalBitmap ?: return
        val framed = applyFrame(base, selectedFrame)
        lastCapturedBitmap = framed
        binding.capturedImageView.setImageBitmap(framed)
    }

    // 프레임 종류에 따라 알맞은 함수로 분기
    private fun applyFrame(source: Bitmap, frame: FrameType): Bitmap {
        return when (frame) {
            FrameType.NONE -> source
            FrameType.WOOD -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.wood_medium),
                borderWidthRatio = 0.05f,
                cornerSymbol = null
            )
            FrameType.HEART -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.shutter_red),
                borderWidthRatio = 0.018f,
                cornerSymbol = "♥"
            )
            FrameType.STARS -> drawBorderFrame(
                source,
                borderColor = ContextCompat.getColor(this, R.color.wood_gold),
                borderWidthRatio = 0.018f,
                cornerSymbol = "★"
            )
            FrameType.FILMSTRIP -> drawFilmStripFrame(source)
            FrameType.POLAROID -> drawPolaroidFrame(source)
        }
    }

    // 단순 테두리 + (옵션) 네 모서리 장식 문자
    private fun drawBorderFrame(
        source: Bitmap,
        borderColor: Int,
        borderWidthRatio: Float,
        cornerSymbol: String?
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val borderWidth = result.width * borderWidthRatio

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
        }
        val inset = borderWidth / 2f
        canvas.drawRect(inset, inset, result.width - inset, result.height - inset, borderPaint)

        if (cornerSymbol != null) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                textSize = result.width * 0.07f
                textAlign = Paint.Align.CENTER
            }
            val margin = result.width * 0.07f
            canvas.drawText(cornerSymbol, margin, margin, textPaint)
            canvas.drawText(cornerSymbol, result.width - margin, margin, textPaint)
            canvas.drawText(cornerSymbol, margin, result.height - margin / 2f, textPaint)
            canvas.drawText(cornerSymbol, result.width - margin, result.height - margin / 2f, textPaint)
        }
        return result
    }

    // 필름 스트립: 위/아래 검은 띠 + 하얀 사각형 구멍 장식
    private fun drawFilmStripFrame(source: Bitmap): Bitmap {
        val barHeight = (source.height * 0.09f).toInt()
        val result = Bitmap.createBitmap(source.width, source.height + barHeight * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, 0f, barHeight.toFloat(), null)

        val holePaint = Paint().apply { color = Color.WHITE }
        val holeSize = barHeight * 0.5f
        val holeSpacing = source.width / 10f
        var x = holeSpacing / 2f
        while (x < source.width) {
            canvas.drawRect(x, barHeight * 0.25f, x + holeSize, barHeight * 0.75f, holePaint)
            canvas.drawRect(
                x,
                result.height - barHeight * 0.75f,
                x + holeSize,
                result.height - barHeight * 0.25f,
                holePaint
            )
            x += holeSpacing
        }
        return result
    }

    // 폴라로이드: 하얀 여백 + 하단 캡션 문구
    private fun drawPolaroidFrame(source: Bitmap): Bitmap {
        val margin = (source.width * 0.05f).toInt()
        val bottomMargin = (source.height * 0.18f).toInt()
        val result = Bitmap.createBitmap(
            source.width + margin * 2,
            source.height + margin + bottomMargin,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, margin.toFloat(), margin.toFloat(), null)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = bottomMargin * 0.4f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "♥ Family ♥",
            result.width / 2f,
            source.height + margin + bottomMargin * 0.6f,
            textPaint
        )
        return result
    }

    // 안드로이드 표준 인쇄 프레임워크로 전달.
    // 캐논 셀피가 Wi-Fi/Mopria로 이미 연결되어 있으면 인쇄 다이얼로그의
    // 프린터 목록에 자동으로 나타납니다.
    private fun printCapturedPhoto() {
        val bitmap = lastCapturedBitmap ?: return
        val printHelper = PrintHelper(this).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
            colorMode = PrintHelper.COLOR_MODE_COLOR
            orientation = PrintHelper.ORIENTATION_PORTRAIT
        }
        printHelper.printBitmap(getString(R.string.print_job_name), bitmap)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
