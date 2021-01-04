package com.example.moviecam

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.Point
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Size
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min

class CameraHelper {
    private fun checkCameraHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)


    companion object {

        data class CameraInfo(
            val name: String,
            val cameraId: String,
            val size: Size,
            val fps: Int)

        private fun lensOrientationString(value: Int) = when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        fun getCameraConfigs(cameraManager: CameraManager):CameraInfo{
            val availableCameras: MutableList<CameraInfo> = mutableListOf()
            cameraManager.cameraIdList[0].let{ id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                    characteristics.get(CameraCharacteristics.LENS_FACING)!!)

                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

                if (capabilities.contains(
                        CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    // Recording should always be done in the most efficient format, which is
                    //  the format native to the camera framework
                    val targetClass = MediaRecorder::class.java

                    // For each size, list the expected FPS
                    cameraConfig.getOutputSizes(targetClass).forEach { size ->
                        // Get the number of seconds that each frame will take to process
                        val secondsPerFrame =
                            cameraConfig.getOutputMinFrameDuration(targetClass, size) /
                                    1_000_000_000.0
                        // Compute the frames per second to let user select a configuration
                        val fps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
                        val fpsLabel = if (fps > 0) "$fps" else "N/A"
                        availableCameras.add(CameraInfo(
                            "$orientation ($id) $size $fpsLabel FPS", id, size, fps))
                    }
                }
            }

            return availableCameras.get(0)
        }

        class SmartSize(width: Int, height: Int) {
            var size = Size(width, height)
            var long = max(size.width, size.height)
            var short = min(size.width, size.height)
            override fun toString() = "SmartSize(${long}x${short})"
        }

        val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

        fun getDisplaySmartSize(display: Display): SmartSize {
            val outPoint = Point()
            display.getRealSize(outPoint)
            return SmartSize(outPoint.x, outPoint.y)
        }

        fun <T>getPreviewOutputSize(
            display: Display,
            characteristics: CameraCharacteristics,
            targetClass: Class<T>,
            format: Int? = null
        ): Size {

            // Find which is smaller: screen or 1080p
            val screenSize = getDisplaySmartSize(display)
            val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
            val maxSize = if (hdScreen) SIZE_1080P else screenSize

            // If image format is provided, use it to determine supported sizes; else use target class
            val config = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            if (format == null)
                assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
            else
                assert(config.isOutputSupportedFor(format))
            val allSizes = if (format == null)
                config.getOutputSizes(targetClass) else config.getOutputSizes(format)

            // Get available sizes and sort them by area from largest to smallest
            val validSizes = allSizes
                .sortedWith(compareBy { it.height * it.width })
                .map { SmartSize(it.width, it.height) }.reversed()

            // Then, get the largest output size that is smaller or equal than our max size
            return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
        }



    }}
