package org.tensorflow.lite.examples.modelpersonalization.fragments

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.tensorflow.lite.examples.modelpersonalization.MainViewModel
import org.tensorflow.lite.examples.modelpersonalization.TransferLearningHelper
import org.tensorflow.lite.examples.modelpersonalization.databinding.FragmentCameraBinding
import org.tensorflow.lite.support.label.Category
import java.io.File
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage

class CameraFragment : Fragment(), TransferLearningHelper.ClassifierListener {

    companion object {
        private const val TAG = "Model Personalization"
        private const val MAX_TRAINING_PER_CLASS = 16   // 62 × 8 = 496 training images
        private const val MAX_VALIDATION_PER_CLASS = 4  // 62 × 2 = 124 validation images
        private const val MAX_TEST_PER_CLASS = 7
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!


    private lateinit var transferLearningHelper: TransferLearningHelper
//    private val viewModel: MainViewModel by activityViewModels()
    private var isTraining = false


    private val images = mutableListOf<Pair<Uri, Int>>()
    private val validationImages = mutableListOf<Pair<Uri, Int>>()
    private val testImages = mutableListOf<Pair<Uri, Int>>()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        transferLearningHelper = TransferLearningHelper(
            numThreads = 1,
            context = requireContext(),
            classifierListener = this
        )


        setupUI()
        loadDataset()
    }

    private fun setupUI() {
        fragmentCameraBinding.startTrainingButton.apply {
            isEnabled = false
            setOnClickListener {
                if (!isTraining) {
                    startRealBatchTraining()
                }
            }
        }
    }

    private fun startRealBatchTraining() {
        isTraining = true
        fragmentCameraBinding.apply {
            startTrainingButton.text = "Training..."
            startTrainingButton.isEnabled = false
        }

        Thread {
            val BATCH_SIZE = 16
            val NUM_EPOCHS = 25


            val PATIENCE = 3
            var patienceCounter = 0
            var bestValidationAccuracy = 0f
            var finalEpochs = NUM_EPOCHS


            try {

                for (epoch in 1..NUM_EPOCHS) {
                    var totalLoss = 0f
                    var batchesProcessed = 0

                    val shuffledTrainingUris = images.shuffled()
                    val trainingBatches = shuffledTrainingUris.chunked(BATCH_SIZE)

                    activity?.runOnUiThread {
                        fragmentCameraBinding.trainingStatusText.text = "Epoch $epoch/$NUM_EPOCHS - Training..."
                    }

                    trainingBatches.forEach { batchUris ->
                        val bottleneckBatch = mutableListOf<FloatArray>()
                        val labelBatch = mutableListOf<FloatArray>()

                        batchUris.forEach { (uri, labelIndex) ->
                            loadBitmapFromUri(uri)?.let { bitmap ->
                                val tensorImage = processImageToTensorImage(bitmap)
                                val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)
                                val label = createOneHotLabel(labelIndex)
                                bottleneckBatch.add(bottleneck)
                                labelBatch.add(label)
                                bitmap.recycle()
                            }
                        }

                        if (bottleneckBatch.isNotEmpty()) {
                            val batchLoss = transferLearningHelper.trainOnBatch(bottleneckBatch, labelBatch)
                            totalLoss += batchLoss
                            batchesProcessed++
                        }
                    }

                    val avgLoss = if (batchesProcessed > 0) totalLoss / batchesProcessed else 0f

                    val validationAccuracy = runValidationInBatches()

                    activity?.runOnUiThread {
                        onEpochCompleted(epoch, avgLoss, validationAccuracy)
                    }

                    if (validationAccuracy > bestValidationAccuracy) {
                        bestValidationAccuracy = validationAccuracy
                        patienceCounter = 0
                        Log.d(TAG, "Validation accuracy improved to: $bestValidationAccuracy")
                    } else {
                        patienceCounter++
                        Log.d(TAG, "No improvement. Patience: $patienceCounter/$PATIENCE")
                    }

                    if (patienceCounter >= PATIENCE) {
                        Log.d(TAG, "Early stopping triggered at epoch $epoch.")
                        finalEpochs = epoch

                        activity?.runOnUiThread {
                            onEarlyStopping(epoch, bestValidationAccuracy, "No improvement for $PATIENCE epochs")
                        }
                        break
                    }
                }

                Log.d(TAG, "Training loop finished. Evaluating test set...")
                val finalTestAccuracy = runTestEvaluationInBatches()
                Log.e(TAG, "Final Test Accuracy: $finalTestAccuracy")

                if (patienceCounter < PATIENCE) {
                    activity?.runOnUiThread {
                        onTrainingCompleted(
                            finalEpochs,
                            bestValidationAccuracy,
                            finalTestAccuracy
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during training: ${e.message}", e)
                activity?.runOnUiThread { onError("Training failed: ${e.message}") }
            }
        }.start()
    }


    private fun runValidationInBatches(): Float {
        var correctPredictions = 0
        val BATCH_SIZE = 16

        validationImages.chunked(BATCH_SIZE).forEach { validationBatchUris ->
            validationBatchUris.forEach { (uri, labelIndex) ->
                loadBitmapFromUri(uri)?.let { bitmap ->
                    val tensorImage = processImageToTensorImage(bitmap)
                    val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)

                    val results = transferLearningHelper.classifyWithBottleneck(bottleneck)
                    val predictedClassIndex = if (results.isNotEmpty()) getFolderLabelIndex(results[0].label) else -1

                    if (predictedClassIndex == labelIndex) {
                        correctPredictions++
                    }
                    bitmap.recycle()
                }
            }
        }

        return if (validationImages.isNotEmpty()) correctPredictions.toFloat() / validationImages.size else 0f
    }

    private fun runTestEvaluationInBatches(): Float {
        if (testImages.isEmpty()) return 0f

        var correctPredictions = 0
        val BATCH_SIZE = 16

        testImages.chunked(BATCH_SIZE).forEach { testBatchUris ->
            testBatchUris.forEach { (uri, labelIndex) ->
                loadBitmapFromUri(uri)?.let { bitmap ->
                    val tensorImage = processImageToTensorImage(bitmap)
                    val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)

                    val results = transferLearningHelper.classifyWithBottleneck(bottleneck)
                    val predictedClassIndex = if (results.isNotEmpty()) getFolderLabelIndex(results[0].label) else -1

                    if (predictedClassIndex == labelIndex) {
                        correctPredictions++
                    }
                    bitmap.recycle()
                }
            }
        }

        return correctPredictions.toFloat() / testImages.size
    }



    private fun logMemoryUsage(context: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val percentUsed = (usedMemory * 100) / maxMemory

        Log.d(TAG, "$context - Memory: ${usedMemory}MB/${maxMemory}MB ($percentUsed%)")
    }

    private fun processImageToTensorImage(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(NormalizeOp(0f, 255f))
            .build()

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        return imageProcessor.process(tensorImage)
    }

    private fun createOneHotLabel(labelIndex: Int): FloatArray {
        val label = FloatArray(62) { 0f }
        label[labelIndex] = 1f
        return label
    }

    private fun loadDataset() {
        fragmentCameraBinding.trainingStatusText.text = "Loading dataset..."

        Thread {
            try {
                images.clear()
                validationImages.clear()
                testImages.clear()

                val cachePath = copyFileOrDir("images")
                Log.d(TAG, "Images copied to: $cachePath")

                scanDirectoryForDataset(File(cachePath, "images_train"), images, MAX_TRAINING_PER_CLASS)
                scanDirectoryForDataset(File(cachePath, "images_validation"), validationImages, MAX_VALIDATION_PER_CLASS)
                scanDirectoryForDataset(File(cachePath, "images_test"), testImages, MAX_TEST_PER_CLASS)

                activity?.runOnUiThread {
                    val totalTraining = images.size
                    val totalValidation = validationImages.size
                    val totalTest = testImages.size

                    Log.d(TAG, "Dataset loaded: $totalTraining training, $totalValidation validation, $totalTest test images")
                    fragmentCameraBinding.trainingStatusText.text =
                        "Ready! $totalTraining train, $totalValidation val, $totalTest test"

                    if (totalTraining > 0) {
                        fragmentCameraBinding.startTrainingButton.isEnabled = true
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading dataset: ${e.message}")
            }
        }.start()
    }

    private fun scanDirectoryForDataset(directory: File, list: MutableList<Pair<Uri, Int>>, maxPerClass: Int) {
        if (!directory.exists()) {
            Log.w(TAG, "Directory not found: ${directory.path}")
            return
        }

        directory.listFiles()?.forEach { classFolder ->
            if (classFolder.isDirectory) {
                val labelIndex = getFolderLabelIndex(classFolder.name)
                if (labelIndex != -1) {
                    var count = 0
                    classFolder.listFiles()
                        ?.filter { it.name.lowercase().endsWith(".jpg") }
                        ?.forEach { imageFile ->
                            if (count < maxPerClass) {
                                list.add(Pair(Uri.fromFile(imageFile), labelIndex))
                                count++
                            }
                        }
                }
            }
        }
    }

    // FUNCTION for Preloaded Feature Training

//    private fun loadDataset() {
//        fragmentCameraBinding.trainingStatusText.text = "Loading dataset..."
//
//        Thread {
//            try {
//                images.clear()
//                validationImages.clear()
//
//                val cachePath = copyFileOrDir("images")
//                Log.d(TAG, "Images copied to: $cachePath")
//
//                val imageDir = File(cachePath)
//                if (imageDir.exists()) {
//                    imageDir.listFiles()?.forEach { classFolder ->
//                        if (classFolder.isDirectory) {
//                            val folderName = classFolder.name
//                            val labelIndex = getFolderLabelIndex(folderName)
//                            Log.e(TAG, "DIRECTORY $folderName INDEX $labelIndex")
//
//                            if (labelIndex != -1) {
//                                var trainingCount = 0
//                                var validationCount = 0
//
//                                val allFiles = classFolder.listFiles()
//                                    ?.filter { it.name.lowercase().endsWith(".jpg") }
//                                    ?.shuffled()
//
//                                allFiles?.forEach { imageFile ->
//                                    Log.d(TAG, "Processing file: ${imageFile.name} + ${imageFile.absolutePath}")
//                                    val sampleType = getSampleType(imageFile.name)
//                                    val imageUri = Uri.fromFile(imageFile)
//                                    Log.d(TAG, "Processing ImageURI: PARRR ${imageUri}, ${labelIndex}")
//
//                                    when (sampleType) {
//                                        "training" -> {
//                                            if (trainingCount < MAX_TRAINING_PER_CLASS) {
//                                                images.add(Pair(imageUri, labelIndex))
//                                                trainingCount++
//                                            }
//                                        }
//                                        "validation" -> {
//                                            if (validationCount < MAX_VALIDATION_PER_CLASS) {
//                                                validationImages.add(Pair(imageUri, labelIndex))
//                                                validationCount++
//                                            }
//                                        }
//                                    }
//                                }
//
//                                Log.w(TAG, "Class $folderName: $trainingCount training, $validationCount validation")
//                            } else {
//                                Log.e(TAG, "Skipping unknown folder: $folderName")
//                            }
//                        }
//                    }
//                }
//
//                // Update UI with dataset info
//                activity?.runOnUiThread {
//                    val totalTraining = images.size
//                    val totalValidation = validationImages.size
//
//                    Log.d(TAG, "Reduced dataset: $totalTraining training, $totalValidation validation images")
//                    fragmentCameraBinding.trainingStatusText.text =
//                        "Ready! $totalTraining training, $totalValidation validation samples (reduced)"
//
//                    if (totalTraining > 0) {
//                        fragmentCameraBinding.startTrainingButton.isEnabled = true
//                    }
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Error loading dataset: ${e.message}")
//                activity?.runOnUiThread {
//                    fragmentCameraBinding.trainingStatusText.text = "Error loading dataset"
//                    Toast.makeText(context, "Error loading dataset: ${e.message}", Toast.LENGTH_LONG).show()
//                }
//            }
//        }.start()
//    }


    // Function for Preloaded Feature Training

    private fun startChunkedTraining() {
        isTraining = true
        fragmentCameraBinding.apply {
            startTrainingButton.text = "Processing..."
            trainingStatusText.text = "Processing ALL data as bottlenecks in chunks..."
        }

        Thread {
            try {
                val TRAINING_CHUNK_SIZE = 150
                val SHARED_CHUNK_SIZE = 50


                val totalTrainingChunks = (images.size + TRAINING_CHUNK_SIZE - 1) / TRAINING_CHUNK_SIZE

                images.chunked(TRAINING_CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
                    activity?.runOnUiThread {
                        fragmentCameraBinding.trainingStatusText.text =
                            "Training chunk ${chunkIndex + 1}/$totalTrainingChunks (${chunk.size} images)"
                    }

                    val chunkBottlenecks = mutableListOf<Pair<FloatArray, FloatArray>>()

                    chunk.forEach { (uri, labelIndex) ->
                        loadBitmapFromUri(uri)?.let { bitmap ->
                            val tensorImage = processImageToTensorImage(bitmap)
                            val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)
                            val label = createOneHotLabel(labelIndex)

                            chunkBottlenecks.add(Pair(bottleneck, label))
                            bitmap.recycle()
                        }
                    }

                    chunkBottlenecks.forEach { (bottleneck, label) ->
                        transferLearningHelper.addPrecomputedSample(bottleneck, label)
                    }

                    chunkBottlenecks.clear()
                    System.gc()
                    Thread.sleep(300)

                    logMemoryUsage("Training chunk $chunkIndex completed")
                }

                val totalValidationChunks = (validationImages.size + SHARED_CHUNK_SIZE - 1) / SHARED_CHUNK_SIZE

                validationImages.chunked(SHARED_CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
                    activity?.runOnUiThread {
                        fragmentCameraBinding.trainingStatusText.text =
                            "Validation chunk ${chunkIndex + 1}/$totalValidationChunks (${chunk.size} images)"
                    }

                    chunk.forEach { (uri, labelIndex) ->
                        loadBitmapFromUri(uri)?.let { bitmap ->
                            val tensorImage = processImageToTensorImage(bitmap)
                            val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)
                            val label = createOneHotLabel(labelIndex)
                            transferLearningHelper.addValidationBottleneck(bottleneck, label)
                            bitmap.recycle()
                        }
                    }

                    System.gc()
                    Thread.sleep(200)

                    logMemoryUsage("Validation chunk $chunkIndex completed")
                }

                val totalTestChunks = (testImages.size + SHARED_CHUNK_SIZE - 1) / SHARED_CHUNK_SIZE
                testImages.chunked(SHARED_CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
                    activity?.runOnUiThread {
                        fragmentCameraBinding.trainingStatusText.text =
                            "Test chunk ${chunkIndex + 1}/$totalTestChunks (${chunk.size} images)"
                    }
                    chunk.forEach { (uri, labelIndex) ->
                        loadBitmapFromUri(uri)?.let { bitmap ->
                            val tensorImage = processImageToTensorImage(bitmap)
                            val bottleneck = transferLearningHelper.extractBottleneck(tensorImage)
                            val label = createOneHotLabel(labelIndex)
                            transferLearningHelper.addTestBottleneck(bottleneck, label)
                            bitmap.recycle()
                        }
                    }
                    System.gc()
                    Thread.sleep(200)
                    logMemoryUsage("Test chunk $chunkIndex completed")
                }

                Log.d(TAG, "Test data processed.")

                activity?.runOnUiThread {
                    val totalTraining = images.size
                    val totalValidation = validationImages.size
                    val totalTest = testImages.size
                    fragmentCameraBinding.trainingStatusText.text =
                        "Ready! $totalTraining train, $totalValidation val, $totalTest test"
                    transferLearningHelper.startTraining(maxEpochs = 25)
                }

            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Memory error: ${e.message}")
                activity?.runOnUiThread {
                    fragmentCameraBinding.trainingStatusText.text = "Memory error - reduce chunk size"
                    resetTrainingUI()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                activity?.runOnUiThread { resetTrainingUI() }
            }
        }.start()
    }



    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: ${e.message}")
            null
        }
    }


    private fun getFolderLabelIndex(folderName: String): Int {
        return when {
            folderName in "0".."9" -> folderName.toInt()
            folderName in "A".."Z" -> folderName[0] - 'A' + 10

            folderName.startsWith("lowercase_") -> {
                val letter = folderName.removePrefix("lowercase_")
                if (letter.length == 1 && letter[0] in 'a'..'z') {
                    letter[0] - 'a' + 36
                } else {
                    Log.w(TAG, "Invalid lowercase folder format: $folderName")
                    -1
                }
            }

            else -> {
                Log.w(TAG, "Unknown folder name: $folderName")
                -1
            }
        }
    }


    private fun copyFileOrDir(path: String): String {
        val dst = File("${requireContext().cacheDir}/$path")
        copyAssetFileOrDir(requireContext().assets, path, dst)
        return dst.path
    }

    private fun resetTrainingUI() {
        isTraining = false
        fragmentCameraBinding.apply {
            startTrainingButton.text = "Start Training"
            startTrainingButton.isEnabled = true
            trainingStatusText.text = "Ready to train"
        }
    }



    ///////CALLBACKS FROM TransferLearningHelper.ClassifierListener///////
    override fun onError(error: String) {
        activity?.runOnUiThread {
            Log.e(TAG, "Error: $error")
            Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
            resetTrainingUI()
        }
    }

    override fun onResults(results: List<Category>?, inferenceTime: Long) {
        activity?.runOnUiThread {
            results?.let { categories ->
                Log.d(TAG, "Inference results: ${categories.map { "${it.label}: ${it.score}" }}")
            }
        }
    }

    override fun onLossResults(lossNumber: Float) {
        activity?.runOnUiThread {
            fragmentCameraBinding.lossText.text = "Loss: %.4f".format(lossNumber)
        }
    }

    override fun onEpochCompleted(epoch: Int, avgLoss: Float, validationAccuracy: Float) {
        activity?.runOnUiThread {
            fragmentCameraBinding.apply {
                epochText.text = "Epoch: $epoch/50"
                lossText.text = "Avg Loss: %.4f".format(avgLoss)
                accuracyText.text = "Validation Accuracy: ${(validationAccuracy * 100).toInt()}%"
                trainingStatusText.text = "Training... Epoch $epoch completed"
            }
        }
        Log.d(TAG, "Live Update - Epoch $epoch: Loss=$avgLoss, Acc=${(validationAccuracy * 100).toInt()}%")
    }

    override fun onEarlyStopping(epoch: Int, bestValidationAccuracy: Float, reason: String) {
        activity?.runOnUiThread {
            isTraining = false
            fragmentCameraBinding.apply {
                startTrainingButton.text = "Start Training"
                startTrainingButton.isEnabled = true
//                trainingStatusText.text = "!!! Early Stopped - Best Acc: ${(bestValidationAccuracy * 100).toInt()}%"
                epochText.text = "Early Stopped at Epoch: $epoch"
            }
            Toast.makeText(context, "Training stopped early", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTrainingCompleted(totalEpochs: Int, finalValidationAccuracy: Float, finalTestAccuracy: Float) {
        activity?.runOnUiThread {
            isTraining = false
            val valAccuracyText = "Final Val Acc: ${(finalValidationAccuracy * 100).toInt()}%"
            val testAccuracyText = "Final Test Acc: ${(finalTestAccuracy * 100).toInt()}%"

            fragmentCameraBinding.apply {
                startTrainingButton.text = "Start Training"
                startTrainingButton.isEnabled = true
                trainingStatusText.text = " Complete! $valAccuracyText | $testAccuracyText"
                epochText.text = "Completed: $totalEpochs epochs"
            }

            Log.d(TAG, " TRAINING COMPLETE: $valAccuracyText | $testAccuracyText")
            Toast.makeText(context, "Training completed! Test Accuracy: ${(finalTestAccuracy * 100).toInt()}%", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        transferLearningHelper.close()
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

//    source: ONNX on device training - image classification  example
    fun copyAssetFileOrDir(assetManager: AssetManager, assetPath: String, dstFileOrDir: File) {
        // This function copies the asset file or directory named by `assetPath` to the file or
        // directory specified by `dstFileOrDir`.
        val assets: Array<String>? = assetManager.list(assetPath)
        if (assets!!.isEmpty()) {
            // asset is a file
            copyAssetFile(assetManager, assetPath, dstFileOrDir)
        } else {
            // asset is a dir. loop over dir and copy all files or sub dirs to cache dir
            for (i in assets.indices) {
                val assetChild = (if (assetPath.isEmpty()) "" else "$assetPath/") + assets[i]
                val dstChild = dstFileOrDir.resolve(assets[i])
                copyAssetFileOrDir(assetManager, assetChild, dstChild)
            }
        }
    }


    //    source: ONNX on device training - image classification  example
    fun copyAssetFile(assetManager: AssetManager, assetPath: String, dstFile: File) {
        check(!dstFile.exists() || dstFile.isFile)

        dstFile.parentFile?.mkdirs()

        val assetContents = assetManager.open(assetPath).use { assetStream ->
            val size: Int = assetStream.available()
            val buffer = ByteArray(size)
            assetStream.read(buffer)
            buffer
        }

        java.io.FileOutputStream(dstFile).use { dstStream ->
            dstStream.write(assetContents)
        }
    }
}
