package org.greenstand.android.TreeTracker.activities


import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.*
import org.greenstand.android.TreeTracker.R
import org.greenstand.android.TreeTracker.camera.CameraPreview
import org.greenstand.android.TreeTracker.utilities.ImageUtils
import org.greenstand.android.TreeTracker.utilities.Utils
import org.greenstand.android.TreeTracker.utilities.ValueHelper
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*


class CameraActivity : AppCompatActivity(), Camera.PictureCallback, View.OnClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback {

    private var mCamera: Camera? = null
    private var mPreview: CameraPreview? = null
    private val TAG = "Camera activity"
    private var mCurrentPhotoPath: String? = null
    private var mImageView: ImageView? = null
    private var captureButton: ImageButton? = null
    private var tmpImageFile: File? = null
    private var safeToTakePicture = true

    private var operationAttempt: Job? = null

    private var captureSelfie: Boolean = false



    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        mImageView = cameraPreviewTaken


        captureButton = buttonCapture

        // Add a listener to the buttons
        captureButton!!.setOnClickListener(this@CameraActivity)

        intent.extras?.let {
            captureSelfie = intent.extras!!.getBoolean(ValueHelper.TAKE_SELFIE_EXTRA, false)
        }

        setSupportActionBar(camera_toolbar)
        supportActionBar?.title = ""
        if(captureSelfie) {
            cameraToolbarTitle.text = getString(R.string.take_a_selfie)
        } else {
            cameraToolbarTitle.text = getString(R.string.add_a_tree)
        }

    }

    override fun onStart() {
        super.onStart()
        operationAttempt?.cancel()
        operationAttempt = GlobalScope.launch(Dispatchers.Main) {

            Timber.i("Opening Camera")

            captureButton?.visibility = View.INVISIBLE

            while(mCamera == null){
                try {

                    if(captureSelfie) {

                        val numberOfCameras = Camera.getNumberOfCameras()
                        if (numberOfCameras > 1) {
                            mCamera = Camera.open(1)
                        } else {
                            mCamera = Camera.open()
                        }

                    } else {

                        mCamera = Camera.open()

                    }
                } catch (e: Exception) {
                    Timber.d("in use" + e.localizedMessage)
                }
                delay(250)
            }
            mPreview = CameraPreview(this@CameraActivity, mCamera)

            //activity_camera.removeAllViews()
            camera_preview.addView(mPreview)
            captureButton?.visibility = View.VISIBLE
        }
    }
    /** Check if this device has a camera  */
    private fun checkCameraHardware(context: Context): Boolean {
        return if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            // this device has a camera
            true
        } else {
            // no camera on this device
            false
        }
    }

    override fun onPictureTaken(data: ByteArray, camera: Camera) {
        captureButton!!.visibility = View.INVISIBLE

        try {
            tmpImageFile = File.createTempFile("tmpimage.jpg", null, cacheDir)
        } catch (e: IOException) {
            Timber.d("file not created")
            e.printStackTrace()
        }

        try {
            val fo = FileOutputStream(tmpImageFile!!)
            fo.write(data)
            fo.close()
        } catch (e: IOException) {
            // TODO Auto-generated catch block
            e.printStackTrace()
        }

        if(captureSelfie){

            var exif: ExifInterface? = null
            try {
                exif = ExifInterface(tmpImageFile!!.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
                exif.saveAttributes()
            } catch (e: IOException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
            }
        } else {
            MainActivity.mImageQuality = testFocusQuality()
        }

        setPic()
        safeToTakePicture = true
        savePicture()      //skip picture preview
        releaseCamera()
    }

    private fun compressImage() {

        if(mCurrentPhotoPath != null) {
            val photo = Utils.resizedImage(mCurrentPhotoPath!!)

            val bytes = ByteArrayOutputStream()
            photo.compress(Bitmap.CompressFormat.JPEG, 70, bytes)

            val f = File(mCurrentPhotoPath!!)
            try {
                f.createNewFile()
                val fo = FileOutputStream(f)
                fo.write(bytes.toByteArray())
                fo.close()
            } catch (e: IOException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()       // release the camera immediately on pause event
    }

    private fun releaseCamera() {
        if (mCamera != null) {
            mCamera?.release()        // release the camera for other applications
            mCamera = null
            Timber.d("camera released")
        }
    }


    private fun setUpPhotoFile(): File {
        val cw = ContextWrapper(applicationContext)
        val f = ImageUtils.createImageFile(cw)
        mCurrentPhotoPath = f.absolutePath

        return f
    }

    /**
     * // Compute average of sum of squares of the gradient in H and V directions.
     */
	private  fun brennersFocusMetric(input: Array<Array<Int>>,rows: Int,cols: Int ) : Double {

        var V = Array(rows) {Array(cols) {0} }
        var H = Array(rows) {Array(cols) {0} }
		for(row in 0 until rows)
		{
			for (col in 0 until cols-2) {
				val grad = input[row][col+2] - input[row][col];
				H[row][col] = grad;
			}
		}

		for(row in 0 until rows-2)
		{
			for (col in 0 until cols) {
				val grad = input[row+2][col] - input[row][col];
				V[row][col] = grad;
			}
		}

		var sum = 0;
		for(row in 0 until rows)
		{
			for (col in 0 until cols) {
                val HRC = H[row][col];
                val VRC = V[row][col];
                if (kotlin.math.abs(HRC) > kotlin.math.abs(VRC)) {
                    sum += HRC * HRC
                }
                else {
                    sum += VRC * VRC
                }
			}
		}
		return sum / (rows * cols).toDouble();
	}

    /**
     *  Get grayscale image using standard formula.
     */
    private fun getGrayPixels(image: Array<Array<Int>>,rows: Int,cols: Int) : Array<Array<Int>> {
        var result = Array(rows) { Array(cols) { 0 } }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var B = image[r][c] and 0x000000FF
                var G = (image[r][c] and 0x0000FF00) shr 8
                var R = (image[r][c] and 0x00FF0000) shr 16

                val gray = (0.2990 * R + 0.5870 * G + 0.1140 * B)
                result[r][c] = gray.toInt()
            }
        }
        return result

    }

    /**
     *  use Brenner's focus metric.
     */
    private fun  testFocusQuality() : Double {
        try {
            val bmOptions = BitmapFactory.Options()

            bmOptions.inSampleSize = 1
            bmOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmOptions.inJustDecodeBounds = false

            /* Decode the JPEG file into a Bitmap */
            val bitmap = BitmapFactory.decodeFile(tmpImageFile!!.absolutePath, bmOptions) ?: return 0.0;
            val rows = bitmap.height
            val cols = bitmap.width
            val bc = bitmap.byteCount
            val pix = IntArray(rows * cols)
            bitmap.getPixels(pix, 0, cols, 0, 0, cols, rows)
            var img = Array(rows) { Array(cols) { 0 } }
            // need to get a grid to calculate gradients.
            var index = 0;
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    img[r][c] = pix[index++]
                }
            }
            // metric only cares about luminance.
            val grayImage = getGrayPixels(img, rows, cols)
            return brennersFocusMetric(grayImage, rows, cols)
        }
        catch(e: java.lang.Exception)
        {
            println(e);
        }
        // TODO on an error, we return very bad focus.
        return 0.0;
    }



    private fun linspace(start: Double, end: Double, n: Int) : Array<Double>
    {
        val result = Array<Double>(n){0.0}
        val nMinus1 = n - 1
        for (i in 0..nMinus1 )
        {
             result[i] = start + (i * (((end - start) /nMinus1) ));
        }
        return result
    }

    private fun grid(start: Double, end: Double, n: Int): Array<Array<Double>>{

        val space =  linspace(start,end,n);
        return Array<Array<Double>>(n) { space }
    }

    /**
     * TODO make a gaussian kernel
     */


    /**
     *  TODO To test the focus detection, we need to blur some images.
     */
   


    private fun setPic() {

        /* There isn't enough memory to open up more than a couple camera photos */
        /* So pre-scale the target bitmap into which the file is decoded */

        /* Get the size of the image */
        val bmOptions = BitmapFactory.Options()
        bmOptions.inJustDecodeBounds = true
        BitmapFactory.decodeFile(tmpImageFile!!.absolutePath, bmOptions)
        val imageWidth = bmOptions.outWidth

        // Calculate your sampleSize based on the requiredWidth and
        // originalWidth
        // For e.g you want the width to stay consistent at 500dp
        val requiredWidth = (500 * resources.displayMetrics.density).toInt()

        var sampleSize = Math.ceil((imageWidth.toFloat() / requiredWidth.toFloat()).toDouble()).toInt()

        Timber.d("sampleSize " + Integer.toString(sampleSize))
        // If the original image is smaller than required, don't sample
        if (sampleSize < 1) {
            sampleSize = 1
        }

        Timber.d("sampleSize 2 " + Integer.toString(sampleSize))
        bmOptions.inSampleSize = sampleSize
        bmOptions.inPurgeable = true
        bmOptions.inPreferredConfig = Bitmap.Config.RGB_565
        bmOptions.inJustDecodeBounds = false

        /* Decode the JPEG file into a Bitmap */
        val bitmap = BitmapFactory.decodeFile(tmpImageFile!!.absolutePath, bmOptions) ?: return


        var exif: ExifInterface? = null
        try {
            exif = ExifInterface(tmpImageFile!!.absolutePath)
        } catch (e: IOException) {
            // TODO Auto-generated catch block
            e.printStackTrace()
        }

        val orientString = exif!!.getAttribute(ExifInterface.TAG_ORIENTATION)
        val orientation = if (orientString != null)
            Integer.parseInt(orientString)
        else
            ExifInterface.ORIENTATION_NORMAL
        var rotationAngle = 0
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90)
            rotationAngle = 90
        if (orientation == ExifInterface.ORIENTATION_ROTATE_180)
            rotationAngle = 180
        if (orientation == ExifInterface.ORIENTATION_ROTATE_270)
            rotationAngle = 270


        val matrix = Matrix()
        matrix.setRotate(rotationAngle.toFloat(), bitmap.width.toFloat() / 2,
                bitmap.height.toFloat() / 2)
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0,
                bmOptions.outWidth, bmOptions.outHeight, matrix, true)

        /* Associate the Bitmap to the ImageView */
        mImageView?.setImageBitmap(rotatedBitmap)
        mImageView?.visibility = View.VISIBLE
    }


    override fun onClick(v: View) {
        v.isHapticFeedbackEnabled = true
        // get an image from the camera
        if (safeToTakePicture && mCamera != null) {     //check mCamera isn't null to avoid error
            safeToTakePicture = false
            mCamera?.takePicture(null, null, this@CameraActivity)
            Timber.d("take pic")
        }
    }

    private fun savePicture() {
        var pictureFile: File?
        try {
            pictureFile = setUpPhotoFile()
            mCurrentPhotoPath = pictureFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            pictureFile = null
            mCurrentPhotoPath = null
        }

        var saved = true
        try {
            val fos = FileOutputStream(pictureFile!!)
            val input: InputStream  = FileInputStream(tmpImageFile)
            val buf = ByteArray(1024)
            var len: Int = input.read(buf)
            while ( len > 0)
            {
                fos.write(buf, 0, len)
                len = input.read(buf)
            }
            fos.close()
            tmpImageFile!!.delete()
            compressImage()
        } catch (e: FileNotFoundException) {
            Timber.tag(TAG).d("File not found: " + e.message)
            saved = false
        } catch (e: IOException) {
            Timber.tag(TAG).d("Error accessing file: " + e.message)
            saved = false
        } catch (e: Exception) {
            Timber.tag(TAG).d("Error accessing file: " + e.message)
            saved = false
        }

        if (saved) {
            val data = Intent()
            data.putExtra(ValueHelper.TAKEN_IMAGE_PATH, mCurrentPhotoPath)
            setResult(Activity.RESULT_OK, data)

        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }




    companion object {

        val MEDIA_TYPE_IMAGE = 1



        /** Create a file Uri for saving an image or video  */
        private fun getOutputMediaFileUri(type: Int): Uri {
            return Uri.fromFile(getOutputMediaFile(type))
        }

        /** Create a File for saving an image or video  */
        private fun getOutputMediaFile(type: Int): File? {
            // To be safe, you should check that the SDCard is mounted
            // using Environment.getExternalStorageState() before doing this.

            val mediaStorageDir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "MyCameraApp")
            // This location works best if you want the created images to be shared
            // between applications and persist after your app has been uninstalled.

            // Create the storage directory if it does not exist
            if (!mediaStorageDir.exists()) {
                if (!mediaStorageDir.mkdirs()) {
                    Timber.d("MyCameraApp failed to create directory")
                    return null
                }
            }

            // Create a media file name
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val mediaFile: File
            if (type == MEDIA_TYPE_IMAGE) {
                mediaFile = File(mediaStorageDir.path + File.separator +
                        "IMG_" + timeStamp + ".jpg")
            } else {
                return null
            }
            return mediaFile
        }
    }
}



