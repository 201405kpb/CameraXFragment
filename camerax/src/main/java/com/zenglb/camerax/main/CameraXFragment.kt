package com.zenglb.camerax.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.common.util.concurrent.ListenableFuture
import com.yeyupiaoling.cameraxapp.view.CameraXPreviewViewTouchListener
import com.yeyupiaoling.cameraxapp.view.FocusImageView
import com.zenglb.camerax.R
import com.zenglb.camerax.main.CameraConfig.Companion.MEDIA_MODE_PHOTO
import com.zenglb.camerax.utils.ANIMATION_FAST_MILLIS
import com.zenglb.camerax.utils.ANIMATION_SLOW_MILLIS
import com.zenglb.camerax.utils.LuminosityAnalyzer
import kotlinx.android.synthetic.main.fragment_camerax.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val KEY_CAMERA_EVENT_ACTION = "key_camera_event_action"
const val KEY_CAMERA_EVENT_EXTRA = "key_camera_event_extra"

private const val CAMERA_CONFIG = "camera_config"    //相机的配置

/**
 * 新版本拍照拍视频方案
 * 解决老版本拍照不清晰，闪光灯,照片删除被系统侦查和流程问题
 *
 *
 * Android库发布至MavenCentral流程详解
 * https://juejin.cn/post/6953598441817636900
 */
class CameraXFragment : Fragment() {

    //这些参数初始化和接口初始化后面再写吧
    fun setCaptureResultListener(listener: CaptureResultListener) {
        this.captureResultListener = listener
    }

    private lateinit var captureResultListener: CaptureResultListener

    //相机的配置：存储路径，闪光灯模式，
    private lateinit var cameraConfig: CameraConfig

//    private var focusView: FocusImageView? = null

    //CameraFragment 对应的XML布局中最外层的ConstraintLayout
    private lateinit var cameraUIContainer: FrameLayout

    private lateinit var cameraPreview: PreviewView
    private lateinit var broadcastManager: LocalBroadcastManager

    private lateinit var cameraSelector: CameraSelector

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var imageCapture: ImageCapture? = null //拍照
    private var videoCapture: VideoCapture? = null //录像用例


    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    // 使用此执行器执行相机阻塞操作
    private lateinit var cameraExecutor: ExecutorService

    //音量下降按钮接收器用于触发快门
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_CAMERA_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    takePhoto()
                }
            }
        }
    }

    /**
     * 横竖屏切换
     *
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraXFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            cameraConfig = it.getParcelable(CAMERA_CONFIG)!!
            if(cameraConfig.mediaMode!=MEDIA_MODE_PHOTO){
                REQUIRED_PERMISSIONS=REQUIRED_PERMISSIONS.plusElement(Manifest.permission.RECORD_AUDIO)
            }
        }

    }


    /**
     * 相机相关的状态初始化
     *
     */
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //CameraFragment 最外层的ConstraintLayout
        cameraUIContainer = view as FrameLayout
        cameraPreview = cameraUIContainer.findViewById(R.id.camera_preview)

//        requireContext()

        // 初始化我们的后台执行器 Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()  //可能需要更多的处理

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // 设置意图过滤器，从我们的main activity接收事件
        val filter = IntentFilter().apply { addAction(KEY_CAMERA_EVENT_ACTION) }

        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // 每当设备的方向改变时，就对用例进行更新旋转
        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

//        // 等待所有的View 都能正确的显示出
//        cameraPreview.post {
//            // Keep track of the display in which this view is attached
//            displayId = cameraPreview.display.displayId
//            // Set up the camera and its use cases
////            setUpCamera()
//        }

        createDir(Environment.getExternalStorageDirectory().toString() + "/cameraX/images")

    }


    /**
     * 初始化摄像头，绑定处理问题
     *
     * Initialize CameraX, and prepare to bind the camera use cases
     */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            //CameraProvider
            cameraProvider = cameraProviderFuture.get()

            //先默认是后置的摄像头，没有后面的再切换到前面的来
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Build and bind the camera use cases
            initCameraUseCases()

        }, ContextCompat.getMainExecutor(requireContext()))
    }


    /**
     * 某些操作不需要完全的初始化吧
     *
     * Declare and bind preview, capture and analysis use cases
     */
    private fun initCameraUseCases() {
        // 获得屏幕的尺寸数据来设置全屏的分辨率
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { cameraPreview.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)

        val size=Size(metrics.widthPixels, metrics.heightPixels)

        val rotation = cameraPreview.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera init failed.")

        // CameraSelector
        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // 预览 Preview
        preview = Preview.Builder()
            // 我们要去宽高比，但是没有分辨率
            .setTargetAspectRatio(screenAspectRatio)
//            .setTargetResolution(size)
            // 设置初始的旋转
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)

            //设置初始目标旋转，如果旋转改变，我们将不得不再次调用它在此用例的生命周期中
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)

            // 我们要求长宽比，但没有分辨率匹配预览配置，但让 CameraX优化为任何特定的解决方案，最适合我们的用例
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
//            .setTargetResolution(size)
            .setFlashMode(cameraConfig.flashMode)
            .build()


        // 视频的还不是很成熟，不一定都能用
        videoCapture = VideoCapture.Builder()//录像用例配置
            .setTargetAspectRatio(screenAspectRatio) //设置高宽比「我比较喜欢全屏」
            //视频帧率  越高视频体积越大
            .setVideoFrameRate(60)
            //bit率  越大视频体积越大
            .setBitRate(3 * 1024 * 1024)
            .setTargetRotation(rotation)//设置旋转角度
//            .setAudioRecordSource(MediaRecorder.AudioSource.MIC)//设置音频源麦克风
            .build()


        val orientationEventListener = object : OrientationEventListener(this.context) {
            override fun onOrientationChanged(orientation : Int) {
                // Monitors orientation values to determine the target rotation value
                val rotation : Int = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                imageCapture?.targetRotation = rotation
            }
        }
        orientationEventListener.enable()

        bindCameraUseCase(0)

        // Attach the viewfinder's surface provider to preview use case
        preview?.setSurfaceProvider(cameraPreview.surfaceProvider)

        initCameraListener()
    }


    /**
     * 相机点击等相关操作监听，焦距操作
     *
     */
    private fun initCameraListener() {
        val zoomState: LiveData<ZoomState>? = camera?.cameraInfo?.zoomState
        val cameraXPreviewViewTouchListener = CameraXPreviewViewTouchListener(this.context)

        cameraXPreviewViewTouchListener.setCustomTouchListener(object :
            CameraXPreviewViewTouchListener.CustomTouchListener {
            // 放大缩小操作
            override fun zoom(delta: Float) {
                Log.d(TAG, "缩放")
                zoomState?.value?.let {
                    val currentZoomRatio = it.zoomRatio
                    camera?.cameraControl!!.setZoomRatio(currentZoomRatio * delta)
                }
            }

            //点击操作
            override fun click(x: Float, y: Float) {
                Log.d(TAG, "单击")
                val factory = camera_preview.meteringPointFactory
                // 设置对焦位置
                val point = factory.createPoint(x, y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    // 3秒内自动调用取消对焦
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                // 执行对焦
                focus_view!!.startFocus(Point(x.toInt(), y.toInt()))
                val future: ListenableFuture<*> = camera?.cameraControl!!.startFocusAndMetering(action)
                future.addListener({
                    try {
                        // 获取对焦结果
                        val result = future.get() as FocusMeteringResult
                        if (result.isFocusSuccessful) {
                            focus_view!!.onFocusSuccess()
                        } else {
                            focus_view!!.onFocusFailed()
                        }
                    } catch (e: java.lang.Exception) {
                        Log.e(TAG, e.toString())
                    }
                }, ContextCompat.getMainExecutor(this@CameraXFragment.requireContext()) )
            }

            // 双击操作
            override fun doubleClick(x: Float, y: Float) {
                Log.d(TAG, "双击")
                // 双击放大缩小
                val currentZoomRatio = zoomState?.value!!.zoomRatio
                if (currentZoomRatio > zoomState.value!!.minZoomRatio) {
                    camera?.cameraControl!!.setLinearZoom(0f)
                } else {
                    camera?.cameraControl!!.setLinearZoom(0.5f)
                }
            }

            override fun longPress(x: Float, y: Float) {
                Log.d(TAG, "长按")
            }
        })
        // 添加监听事件
        camera_preview.setOnTouchListener(cameraXPreviewViewTouchListener)
    }


    /**
     *  检测是4：3 还是16：9 好点
     *
     *  [androidx.camera.core.ImageAnalysis Config] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width   预览的宽
     *  @param height - preview height 预览的高
     *  @return suitable aspect ratio 合适的比例
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    /**
     * 停止录像
     *
     * 录制的视频的时间
     */
    public fun stopTakeVideo(time: Long) {
        //这里是不是会自动的unbind VideoCapture
        videoCapture?.stopRecording()
    }


    /**
     * captureMode
     * 0：拍照
     * 1：拍视频
     */
    private fun bindCameraUseCase(captureMode: Int) {
        // 再次重新绑定前应该先解绑
        cameraProvider?.unbindAll()

        try {
            //目前一次无法绑定拍照和摄像一起
            when (captureMode) {
                //拍照预览的时候尝试图片分析
                0 -> {
                    camera = cameraProvider?.bindToLifecycle(
                        this, cameraSelector,preview, imageCapture
                    )

                }

                1 -> camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
            }

//            // Attach the viewfinder's surface provider to preview use case
//            preview?.setSurfaceProvider(cameraPreview.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }

    }



    /**
     * 拍摄视频移动手指控制缩放,支持中...
     *
     */
    fun zoomTakeVideo() {
//        videoCapture?
    }

    /**
     * 拍摄视频，目前还没有稳定，先初步的支持吧
     *
     */
    fun takeVideo() {
        bindCameraUseCase(1)
        val videoFile = createMediaFile(cameraConfig.cacheMediaDir, VIDEO_EXTENSION)

        // 设置视频的元数据，这里需要后期再完善吧
        val metadata = VideoCapture.Metadata().apply {

        }

        // Create output options object which contains file + metadata
        // 创建输出选项，包含有图片文件和其中的元数据
        val outputOptions = VideoCapture.OutputFileOptions.Builder(videoFile)
            .setMetadata(metadata)
            .build()

        //开始录像
        if (checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
            return
        }else{
            videoCapture?.startRecording(
                outputOptions,
                Executors.newSingleThreadExecutor(),
                object : VideoCapture.OnVideoSavedCallback {
                    override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                        Log.e(TAG, "onVideoSaved: ${outputFileResults.savedUri?.path.toString()}")
                        flushMedia(outputFileResults.savedUri)

                        Handler(Looper.getMainLooper()).post(Runnable {
                            bindCameraUseCase(0)
                        })

                        captureResultListener.onVideoRecorded(outputFileResults.savedUri?.path.toString())
                    }

                    override fun onError(error: Int, message: String, cause: Throwable?) {
                        Handler(Looper.getMainLooper()).post(Runnable {
                            bindCameraUseCase(0)
                        })
                    }
                })
        }

    }


    /**
     * 让媒体资源马上可以浏览
     */
    private fun flushMedia(savedMediaUri: Uri?) {
        //刷新手机图片，App 才能预览到最新的图
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            requireActivity().sendBroadcast(
                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedMediaUri)
            )
        }

        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(savedMediaUri?.toFile()?.extension)

        //刷新，通知文件系统
        MediaScannerConnection.scanFile(
            context,
            arrayOf(savedMediaUri?.toFile()?.absolutePath),
            arrayOf(mimeType)
        ) { _, uri ->
            Log.d(TAG, "Image capture scanned into media store: $uri")
        }

    }

    /**
     * 切换前后摄像头
     *
     */
    fun switchCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        // Re-bind use cases to update selected camera
        initCameraUseCases()
    }


    /**
     * 切换闪光模式,  打开，关闭，自动，长亮
     *
     */
    fun switchFlashMode() :Int{
        when (cameraConfig.flashMode) {
            FLASH_MODE_OFF -> cameraConfig.flashMode=FLASH_MODE_ON
            FLASH_MODE_ON -> cameraConfig.flashMode=FLASH_MODE_OFF
            FLASH_MODE_AUTO -> cameraConfig.flashMode=FLASH_MODE_OFF
        }
        // Re-bind use cases to update selected camera
        initCameraUseCases()
        return cameraConfig.flashMode
    }


    /**
     * 切换闪光模式,  打开，关闭，自动，长亮
     *
     */
    fun setFlashMode(flashMode:Int) :Int{
        if(flashMode==FLASH_ALL_ON){
            cameraConfig.flashMode=FLASH_MODE_OFF
            camera?.cameraControl?.enableTorch(true)
        }else{
            cameraConfig.flashMode=flashMode
            camera?.cameraControl?.enableTorch(false)
            initCameraUseCases()
        }
        return cameraConfig.flashMode
    }


    /**
     * 拍照处理方法(这里只是拍照，录制视频另外有方法)
     *
     */
    fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->

            val photoFile = createMediaFile(cameraConfig.cacheMediaDir, PHOTO_EXTENSION)


            // 设置拍照的元数据
            val metadata = ImageCapture.Metadata().apply {
                // 用前置摄像头的话要镜像画面
                // Mirror image when using the front camera
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            // Create output options object which contains file + metadata
            // 创建输出选项，包含有图片文件和其中的元数据
            val outputOptions = OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // 设置拍照监听回调，当拍照动作被触发的时候
            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: OutputFileResults) {
                        indicateSuccess()  //移动到这里吧
                        //我就没有看见 output.savedUri 有过正常的数据
                        val savedUriPath = output.savedUri ?: Uri.fromFile(photoFile)
                        captureResultListener.onPhotoTaken(savedUriPath.path.toString())
                        flushMedia(savedUriPath)
                    }
                })
        }
    }

    /**
     * 拍照显示成功的提示
     *
     */
    private fun indicateSuccess() {
        // 显示一个闪光动画来告知用户照片已经拍好了。在华为等手机上好像有点问题啊 cameraPreview
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cameraUIContainer.postDelayed({
                cameraUIContainer.foreground = ColorDrawable(Color.WHITE)
                cameraUIContainer.postDelayed(
                    { cameraUIContainer.foreground = null },
                    ANIMATION_FAST_MILLIS
                )
            }, ANIMATION_SLOW_MILLIS)
        }
    }


    /**
     * 是否有两个摄像头可以用来切换
     *
     */
    fun canSwitchCamera(): Boolean {
        return hasBackCamera() && hasFrontCamera()
    }


    /**
     * 检查设备是否有后置摄像头
     */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /**
     * 检查设备是否有前置摄像头
     */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }


    /**
     * onResume，回来需要检查一下权限是不是别关闭 回收了之类的
     *
     * private const val PERMISSIONS_REQUEST_CODE = 10
     */
    override fun onResume() {
        super.onResume()

        if (!hasPermissions(requireContext())) {
            // Request camera-related permissions
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        } else {

            // 等待所有的View 都能正确的显示出
            cameraPreview.post {
                // Keep track of the display in which this view is attached
                displayId = cameraPreview.display.displayId
                // Set up the camera and its use cases
                setUpCamera()
            }
        }
    }


    /**
     *  请求相机权限
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //这里重新初始化相机 ？？？
                setUpCamera()
            } else {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }
    }


    /**
     * 销毁后各种处理后事啊
     *
     */
    override fun onDestroyView() {
        super.onDestroyView()
        // Shut down our background executor
        cameraExecutor.shutdown()
        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)

    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camerax, container, false)
    }


    companion object {
        private const val TAG = "CameraXFragment"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val PERMISSIONS_REQUEST_CODE = 10
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        public  const val FLASH_ALL_ON = 777



        private var REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context) = REQUIRED_PERMISSIONS.all {
            checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 产生的素材都将统一放在Lebang 文件下，后续需要清楚才好管理
         *
         * @return
         * @throws IOException
         */
        private fun createMediaFile(baseFolder: String?, format: String): File {
            val timeStamp = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
            createDir(baseFolder)
            return File(baseFolder + timeStamp + format)
//            return File.createTempFile(timeStamp ,format,File(baseFolder))
        }

        /**
         * 创建图片目录
         *
         * @param dirPath
         * @return
         */
        private fun createDir(dirPath: String?): Boolean {
            //判断为空的目录的情况用默认目录。。。。
            val file = File(dirPath)
            return if (!file.exists() || !file.isDirectory) {
                file.mkdirs()
            } else
                true
        }

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param cameraConfig Parameter 1.
         * @return A new instance of fragment CameraXFragment.
         */
        @JvmStatic
        fun newInstance(cameraConfig: CameraConfig) =
            CameraXFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(CAMERA_CONFIG, cameraConfig)
                }
            }
    }


}