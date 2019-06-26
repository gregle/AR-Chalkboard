package com.gregle.chalkboardwall

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.*
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.graphics.rotationMatrix
import androidx.core.graphics.transform
import com.google.ar.core.*
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.rendering.ExternalTexture
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import java.io.IOException
import java.util.HashMap

open class ArVideoFragment : ArFragment() {

    private lateinit var externalTexture: ExternalTexture
    private lateinit var videoRenderable: ModelRenderable

    private val augmentedImageMap = HashMap<AugmentedImage, VideoDetails>()

    private class VideoDetails {
        var videoAnchorNode: VideoAnchorNode = VideoAnchorNode()
        var mediaPlayer: MediaPlayer = MediaPlayer()
        constructor (mediaPlayer: MediaPlayer, videoAnchorNode: VideoAnchorNode) {
            this.videoAnchorNode = videoAnchorNode
            this.mediaPlayer = mediaPlayer
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        planeDiscoveryController.hide()
        planeDiscoveryController.setInstructionView(null)
        arSceneView.planeRenderer.isEnabled = false
        arSceneView.isLightEstimationEnabled = false

        initializeSession()

        return view
    }

    override fun getSessionConfiguration(session: Session): Config {

        fun setupAugmentedImageDatabase(config: Config, session: Session): Boolean {
            try {
                requireContext().assets.open(SAMPLE_IMAGE_DATABASE).use {
                    config.augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, `it`)
                }
                return true
            } catch (e: IOException) {
                Log.e(TAG, "IO exception loading augmented image bitmap.", e)
            }
            return false
        }

        return super.getSessionConfiguration(session).also {
            it.lightEstimationMode = Config.LightEstimationMode.DISABLED
            it.focusMode = Config.FocusMode.AUTO

            if (!setupAugmentedImageDatabase(it, session)) {
                Toast.makeText(requireContext(), "Could not setup augmented image database", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createArScene(augmentedImage: AugmentedImage) {

        val mediaPlayer = MediaPlayer()
        val videoAnchorNode = VideoAnchorNode().apply {
            setParent(arSceneView.scene)
        }

        // Create an ExternalTexture for displaying the contents of the video.
        externalTexture = ExternalTexture().also {
            mediaPlayer.setSurface(it.surface)
        }

        // Create a renderable with a material that has a parameter of type 'samplerExternal' so that
        // it can display an ExternalTexture.
        ModelRenderable.builder()
            .setSource(requireContext(), R.raw.augmented_video_model)
            .build()
            .thenAccept { renderable ->
                videoRenderable = renderable
                renderable.isShadowCaster = false
                renderable.isShadowReceiver = false
                renderable.material.setExternalTexture("videoTexture", externalTexture)

                augmentedImageMap[augmentedImage] = VideoDetails(mediaPlayer, videoAnchorNode)
                playbackArVideo(augmentedImage)
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Could not create ModelRenderable", throwable)
                return@exceptionally null
            }
    }

    override fun onUpdate(frameTime: FrameTime) {
        val frame = arSceneView.arFrame

        // If there is no frame or ARCore is not tracking yet, just return.
        if (frame == null || frame.camera.trackingState != TrackingState.TRACKING) {
            return
        }

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
        for (augmentedImage in updatedAugmentedImages) {
            when (augmentedImage.trackingState) {
                TrackingState.TRACKING -> {
                    // if we detect a new AR anchor that we're not already tracking
                    // create a new video, attach it to the anchor, and add it to the list
                    if (!augmentedImageMap.containsKey(augmentedImage)) {
                        createArScene(augmentedImage)
                    }
                }
                TrackingState.STOPPED -> {
                    // if we've stopped tracking this image remove it
                    dismissArVideo(augmentedImage)
                }
            }
        }
    }

    private fun dismissArVideo(augmentedImage: AugmentedImage) {
        val videoDetails = augmentedImageMap[augmentedImage]
        if (videoDetails != null) {

            videoDetails.videoAnchorNode.anchor?.detach()
            videoDetails.videoAnchorNode.renderable = null
            videoDetails.mediaPlayer.reset()

            augmentedImageMap.remove(augmentedImage)
        }
    }

    private fun playbackArVideo(augmentedImage: AugmentedImage) {
        val videoDetails = augmentedImageMap[augmentedImage]
        val videoAnchorNode = videoDetails!!.videoAnchorNode
        val mediaPlayer = videoDetails.mediaPlayer

        Log.d(TAG, "playbackVideo = ${augmentedImage.name}")
        requireContext().assets.openFd(augmentedImage.name.dropLast(3).plus("mp4"))
            .use { descriptor ->

                val metadataRetriever = MediaMetadataRetriever()
                metadataRetriever.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )

                val videoWidth = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH).toFloatOrNull() ?: 0f
                val videoHeight = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT).toFloatOrNull() ?: 0f
                val videoRotation = metadataRetriever.extractMetadata(METADATA_KEY_VIDEO_ROTATION).toFloatOrNull() ?: 0f

                // Account for video rotation, so that scale logic math works properly
                val imageSize = RectF(0f, 0f, augmentedImage.extentX, augmentedImage.extentZ)
                    .transform(rotationMatrix(videoRotation))

                val videoScaleType = VideoScaleType.CenterCrop

                videoAnchorNode.setVideoProperties(
                    videoWidth = videoWidth, videoHeight = videoHeight, videoRotation = videoRotation,
                    imageWidth = imageSize.width() * 1.05f, imageHeight = imageSize.height() * 1.05f,
                    videoScaleType = videoScaleType
                )

                // Update the material parameters
                videoRenderable.material.setFloat2(MATERIAL_IMAGE_SIZE, imageSize.width(), imageSize.height())
                videoRenderable.material.setFloat2(MATERIAL_VIDEO_SIZE, videoWidth, videoHeight)
                videoRenderable.material.setBoolean(MATERIAL_VIDEO_CROP, VIDEO_CROP_ENABLED)

                mediaPlayer.setDataSource(descriptor)
            }.also {
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            }

        videoAnchorNode.anchor = augmentedImage.createAnchor(augmentedImage.centerPose)

        externalTexture.surfaceTexture.setOnFrameAvailableListener {
            it.setOnFrameAvailableListener(null)
            videoAnchorNode.renderable = videoRenderable
            fadeInVideo()
        }
        augmentedImageMap[augmentedImage]?.mediaPlayer = mediaPlayer
        augmentedImageMap[augmentedImage]?.videoAnchorNode = videoAnchorNode

    }

    private fun fadeInVideo() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = LinearInterpolator()
            addUpdateListener { v ->
                videoRenderable.material.setFloat(MATERIAL_VIDEO_ALPHA, v.animatedValue as Float)
            }
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //Clean Up Work
        for ((key, value) in augmentedImageMap) {
            value.mediaPlayer.release()
        }
    }

    companion object {
        private const val TAG = "ArVideoFragment"

        private const val VIDEO_CROP_ENABLED = true

        private const val MATERIAL_IMAGE_SIZE = "imageSize"
        private const val MATERIAL_VIDEO_SIZE = "videoSize"
        private const val MATERIAL_VIDEO_CROP = "videoCropEnabled"
        private const val MATERIAL_VIDEO_ALPHA = "videoAlpha"
        private const val SAMPLE_IMAGE_DATABASE = "myimages.imgdb"
    }
}