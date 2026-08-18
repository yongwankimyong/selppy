package com.example.woodprintcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lastCapturedBitmap: Bitmap? = null

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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
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

    // ImageProxy(YUV/JPEG) -> Bitmap 변환 + 회전 보정
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun showCapturedPhoto(bitmap: Bitmap) {
        lastCapturedBitmap = bitmap
        binding.capturedImageView.setImageBitmap(bitmap)
        binding.capturedImageView.visibility = android.view.View.VISIBLE
        binding.previewView.visibility = android.view.View.GONE
        binding.shutterButton.visibility = android.view.View.GONE
        binding.resultButtonRow.visibility = android.view.View.VISIBLE
    }

    private fun returnToCameraMode() {
        lastCapturedBitmap = null
        binding.capturedImageView.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        binding.shutterButton.visibility = android.view.View.VISIBLE
        binding.resultButtonRow.visibility = android.view.View.GONE
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
