package com.example.moviecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.moviecam.databinding.ActivityMainBinding
import com.example.moviecam.model.Statuses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val PERMISSIONS_REQUEST_CODE = 10

private val PERMISSIONS_REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
    Manifest.permission.READ_EXTERNAL_STORAGE
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private val cameraManager: CameraManager by lazy {
        val context = this.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val characteristics: CameraCharacteristics by lazy {
        with(cameraManager) {
            getCameraCharacteristics(cameraParams.cameraId)
        }
    }

    private lateinit var recorderSurface: Surface
    private lateinit var recorder: MediaRecorder
    private lateinit var outputFile: File
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraParams: CameraHelper.Companion.CameraInfo by lazy {
        CameraHelper.getCameraConfigs( cameraManager )
    }
    private lateinit var viewFinder: SurfaceView
    private lateinit var session: CameraCaptureSession
    private lateinit var camera: CameraDevice
    private val previewRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(viewFinder.holder.surface)
        }.build()
    }

    private lateinit var  recordRequest: CaptureRequest
//    by lazy {
//        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
//            addTarget(viewFinder.holder.surface)
//            addTarget(recorderSurface)
//            set(
//                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
//                Range(cameraParams.fps, cameraParams.fps)
//            )
//        }.build()
//    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        binding = ActivityMainBinding.inflate(layoutInflater)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        val view = binding.root
        setContentView(view)
        getPermission()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        binding.previewFrame.visibility = View.VISIBLE
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupUi()
            } else {
                getPermission()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder.release()
        recorderSurface.release()
    }

    private fun setupUi() {
        viewModel.status.observe(this) { status ->
            when (status) {
                Statuses.Unknown -> {
                    binding.tvStatusText.setText(status.getText())
                    binding.btStart.isEnabled = false
                    binding.btStop.isEnabled = false
                }
                Statuses.Ready -> {
                    binding.tvStatusText.setText(status.getText())
                    binding.btStart.isEnabled = true
                    binding.btStop.isEnabled = false
                }

                Statuses.OnRec -> {
                    binding.tvStatusText.setText(status.getText())
                    binding.btStart.isEnabled = false
                    binding.btStop.isEnabled = true
                }
                Statuses.RecStopped -> {
                    binding.tvStatusText.text = "${status.getText()} ${outputFile.name}"
                    binding.btStart.isEnabled = true
                    binding.btStop.isEnabled = false

                }

            }
        }

        initViewSurface()


    }

    private fun initViewSurface() {
        viewFinder = binding.previewFrame
        outputFile = createFile(this.applicationContext, "mp4")
        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(">>>>", "camera callback")
                val previewSize = CameraHelper.getPreviewOutputSize(
                    viewFinder.display, characteristics, SurfaceHolder::class.java
                )
                (viewFinder as AutoFitSurfaceView).setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )
                viewFinder.post { initializeCamera() }
            }
        })
    }


    private fun getPermission() {
        if (hasPermissions(this)) {
            setupUi()
        } else {
            binding.previewFrame.visibility = View.GONE
            requestPermissions(PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }
    }
    private fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }



    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (cameraParams.fps > 0) setVideoFrameRate(cameraParams.fps)
        setVideoSize(cameraParams.size.width, cameraParams.size.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    private fun preparerequest():CaptureRequest{
        return session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(viewFinder.holder.surface)
            addTarget(recorderSurface)
            set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(cameraParams.fps, cameraParams.fps)
            )
        }.build()
    }
    private fun initializeCamera() = viewModel.viewModelScope.launch(Dispatchers.Main) {
        camera = openCamera(cameraManager, cameraParams.cameraId, cameraHandler)
        setupPreviewSession()
        setupMediaRecorder()
        binding.btStart.setOnClickListener { view ->
            outputFile = createFile(this@MainActivity.baseContext, "mp4")
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                initRecord()
                recorder.apply {
                    prepare()
                    start()
                    withContext(Dispatchers.Main) {
                        viewModel.status.value = Statuses.OnRec
                    }
                }

            }
        }


        binding.btStop.setOnClickListener { view ->
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                 session.stopRepeating()
                 session.abortCaptures()
                  session.close()
                    recorder.stop()
                   recorder.release()
                setupMediaRecorder()
                MediaScannerConnection.scanFile(
                    view.context, arrayOf(outputFile.absolutePath), null, null
                )
                withContext(Dispatchers.Main) {
                    viewModel.status.value = Statuses.RecStopped
                    setupPreviewSession()
                }
            }}

        viewModel.status.value = Statuses.Ready
        true
    }

    private suspend fun initRecord() {
        val targets = listOf(viewFinder.holder.surface, recorderSurface)
        session = createCaptureSession(camera, targets, cameraHandler)
        recordRequest = preparerequest()
        session.setRepeatingRequest(recordRequest, null, cameraHandler)
    }

    private suspend fun setupPreviewSession() {
        val targets = listOf(viewFinder.holder.surface)
        session = createCaptureSession(camera, targets, cameraHandler)
        session.setRepeatingRequest(previewRequest, null, cameraHandler)
    }

    private fun setupMediaRecorder() {
        recorderSurface = {
            val surface = MediaCodec.createPersistentInputSurface()
            createRecorder(surface).apply {
                prepare()
                release()
            }
            surface
        }.invoke()
        recorder=createRecorder(recorderSurface)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)
            override fun onDisconnected(device: CameraDevice) {
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    companion object {
        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("HH:mm:ss dd-MM-yyyy", Locale.getDefault())
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
                "VID_${sdf.format(Date())}.$extension"
            )
        }
    }


}