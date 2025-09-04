/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.modelpersonalization

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min


class TransferLearningHelper(
    var numThreads: Int = 2,
    val context: Context,
    val classifierListener: ClassifierListener?
) {

    private var interpreter: Interpreter? = null
    private val trainingSamples: MutableList<TrainingSample> = mutableListOf()
    private var executor: ExecutorService? = null

    //This lock guarantees that only one thread is performing training and
    //inference at any point in time.
    private val lock = Any()
    private var targetWidth: Int = 0
    private var targetHeight: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    private val validationSamples: MutableList<TrainingSample> = mutableListOf()
    private val testSamples: MutableList<TrainingSample> = mutableListOf()

    private var bestValidationAccuracy = 0.0f
    private var patienceCounter = 0
    private val patience = 3

    init {
        if (setupModelPersonalization()) {
            targetWidth = interpreter!!.getInputTensor(0).shape()[2]
            targetHeight = interpreter!!.getInputTensor(0).shape()[1]
        } else {
            classifierListener?.onError("TFLite failed to init.")
        }
    }


    fun close() {
        executor?.shutdownNow()
        executor = null
        interpreter = null
    }

    fun pauseTraining() {
        executor?.shutdownNow()
    }

    private fun setupModelPersonalization(): Boolean {
        val options = Interpreter.Options()
        options.numThreads = numThreads
        return try {
            val modelFile = FileUtil.loadMappedFile(context, "handwriting_model.tflite")
            interpreter = Interpreter(modelFile, options)
            debugModelSignaturess()
            true
        } catch (e: IOException) {
            classifierListener?.onError(
                "Model personalization failed to " +
                        "initialize. See error logs for details"
            )
            Log.e(TAG, "TFLite failed to load model with error: " + e.message)
            false
        }
    }

    private fun debugModelSignaturess() {
        interpreter?.let { interp ->
            Log.d(TAG, "Available signatures: ${interp.signatureKeys}")

            try {
                val trainInputs = interp.getSignatureInputs("train")
                val trainOutputs = interp.getSignatureOutputs("train")
                Log.d(TAG, "Train signature inputs: $trainInputs")
                Log.d(TAG, "Train signature outputs: $trainOutputs")

                trainInputs.forEach { inputKey ->
                    val tensor = interp.getInputTensorFromSignature(inputKey, "train")
                    Log.d(TAG, "Input $inputKey - Shape: ${tensor.shape().contentToString()}, DataType: ${tensor.dataType()}")
                }

                trainOutputs.forEach { outputKey ->
                    val tensor = interp.getOutputTensorFromSignature(outputKey, "train")
                    Log.d(TAG, "Output $outputKey - Shape: ${tensor.shape().contentToString()}, DataType: ${tensor.dataType()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error debugging train signature: ${e.message}")
            }
        }
    }


    fun addSample(image: Bitmap, className: String, rotation: Int) {
        synchronized(lock) {
            if (interpreter == null) {
                setupModelPersonalization()
            }
            processInputImage(image, rotation)?.let { tensorImage ->
                val bottleneck = loadBottleneck(tensorImage)
               Log.d(TAG, "TRAINING Bottleneck batch size: ${bottleneck.size} with label name $className}, vo encoding e {${encoding(classes.getValue(className)).contentToString()}}")
                Log.d(TAG, "TRAINING sample added for class $className value $classes.getValue(className)")
                trainingSamples.add(
                    TrainingSample(
                        bottleneck,
                        encoding(classes.getValue(className))
                    )
                )
            }
        }
    }

    fun addValidationSample(image: Bitmap, className: String, rotation: Int) {
        synchronized(lock) {
            if (interpreter == null) {
                setupModelPersonalization()
            }
            processInputImage(image, rotation)?.let { tensorImage ->
                val bottleneck = loadBottleneck(tensorImage)
                Log.d(TAG, "VALIDATION Bottleneck batch size: ${bottleneck.size} with label name $className}, vo encoding e {${encoding(classes.getValue(className)).contentToString()}}")

                Log.d(TAG, "Validation sample added for class $className value $classes.getValue(className)")
                validationSamples.add(
                    TrainingSample(
                        bottleneck,
                        encoding(classes.getValue(className)),
                        null
                    )
                )
            }
        }
    }

    fun trainOnBatch(batchBottlenecks: List<FloatArray>, batchLabels: List<FloatArray>): Float {
        if (batchBottlenecks.size != batchLabels.size) {
            throw IllegalArgumentException("Bottlenecks and labels must have the same size.")
        }
        if (batchBottlenecks.isEmpty()) {
            Log.e(TAG, "TFHelper: Empty batch provided for training.")
            return 0f
        }

        return training(batchBottlenecks.toMutableList(), batchLabels.toMutableList())
    }

    fun extractBottleneck(image: TensorImage): FloatArray {
        synchronized(lock) {
            val inputs: MutableMap<String, Any> = HashMap()
            inputs[LOAD_BOTTLENECK_INPUT_KEY] = image.buffer
            val outputs: MutableMap<String, Any> = HashMap()
            val bottleneck = Array(1) { FloatArray(BOTTLENECK_SIZE) }
            outputs[LOAD_BOTTLENECK_OUTPUT_KEY] = bottleneck
            interpreter?.runSignature(inputs, outputs, LOAD_BOTTLENECK_KEY)
            return bottleneck[0]
        }
    }

    fun classifyWithBottleneck(bottleneck: FloatArray): List<Category> {
        synchronized(lock) {
            if (interpreter == null) {
                setupModelPersonalization()
            }

            val inputs: MutableMap<String, Any> = HashMap()
            inputs[INFERENCE_BOTTLENECK_INPUT_KEY] = arrayOf(bottleneck)

            val outputs: MutableMap<String, Any> = HashMap()
            val output = Array(1) { FloatArray(62) }
            outputs[INFERENCE_BOTTLENECK_OUTPUT_KEY] = output

            interpreter?.runSignature(inputs, outputs, INFERENCE_BOTTLENECK_KEY)

            val results = mutableListOf<Category>()
            val classNames = classes.keys.toList()

            output[0].forEachIndexed { index, score ->
                if (score > 0.01f) {
                    results.add(Category(classNames[index], score))
                }
            }

            return results.sortedByDescending { it.score }.take(5)
        }
    }


    fun addPrecomputedSample(bottleneck: FloatArray, label: FloatArray) {
        synchronized(lock) {
            trainingSamples.add(TrainingSample(bottleneck, label))
        }
    }


    fun startTraining(maxEpochs: Int = 50) {
        if (interpreter == null) {
            setupModelPersonalization()
        }

        bestValidationAccuracy = 0.0f
        patienceCounter = 0

        executor = Executors.newSingleThreadExecutor()
        val trainBatchSize = getTrainBatchSize()

        if (trainingSamples.size < trainBatchSize) {
            throw RuntimeException(
                String.format(
                    "Too few samples to start training: need %d, got %d",
                    trainBatchSize, trainingSamples.size
                )
            )
        }

        if (validationSamples.isEmpty()) {
            Log.w(TAG, "No validation samples provided. Early stopping disabled.")
        }

        executor?.execute {
            synchronized(lock) {
                var currentEpoch = 0
                Log.e(TAG, "BATCH size $trainBatchSize, Start training with ${trainingSamples.size} samples,")

                while (currentEpoch < maxEpochs && executor?.isShutdown == false) {
                    var totalLoss = 0f
                    var numBatchesProcessed = 0

                    trainingSamples.shuffle()

                    // Training phase
                    trainingBatches(trainBatchSize).forEach { trainingSamples ->
                        val trainingBatchBottlenecks = MutableList(trainBatchSize) {
                            FloatArray(BOTTLENECK_SIZE)
                        }
                        val trainingBatchLabels = MutableList(trainBatchSize) {
                            FloatArray(62)
                        }

                        trainingSamples.forEachIndexed { index, trainingSample ->
                            trainingBatchBottlenecks[index] = trainingSample.bottleneck
                            trainingBatchLabels[index] = trainingSample.label
                        }

                        val loss = training(trainingBatchBottlenecks, trainingBatchLabels)
                        totalLoss += loss
                        numBatchesProcessed++

                        handler.post {
                            classifierListener?.onLossResults(loss)
                        }
                    }

                    val avgLoss = totalLoss / numBatchesProcessed
                    currentEpoch++

                    // Validation phase
                    val validationAccuracy = if (validationSamples.isNotEmpty()) {
                        calculateValidationAccuracy()
                    } else {
                        0.0f
                    }

                    if(validationAccuracy==0.0f){
                        Log.e(TAG, "Validation accuracy is 0. This might indicate an issue with validation data or processing.")
                    }

                    // Early stopping
                    var shouldStop = false
                    if (validationSamples.isNotEmpty()) {
                        if (validationAccuracy > bestValidationAccuracy) {
                            bestValidationAccuracy = validationAccuracy
                            patienceCounter = 0
                            Log.d(TAG, "New best validation accuracy: $bestValidationAccuracy")
                        } else {
                            patienceCounter++
                            Log.d(TAG, "No improvement. Patience: $patienceCounter/$patience")

                            if (patienceCounter >= patience) {
                                shouldStop = true
                                Log.d(TAG, "Early stopping triggered at epoch $currentEpoch")
                            }
                        }
                    }

                    // Update UI
                    handler.post {
                        classifierListener?.onLossResults(avgLoss)
                        classifierListener?.onEpochCompleted(
                            currentEpoch,
                            avgLoss,
                            validationAccuracy
                        )

                        if (shouldStop) {
                            classifierListener?.onEarlyStopping(
                                currentEpoch,
                                bestValidationAccuracy,
                                "No improvement for $patience epochs"
                            )
                        }
                    }

                    // Stop training if early stopping triggered
                    if (shouldStop) break
                }

                Log.d(TAG, "Training loop ended after $currentEpoch epochs. Evaluation on test set started")
                val testAccuracy = evaluateTestSet()
                // Training completed
                handler.post {
                    classifierListener?.onTrainingCompleted(currentEpoch, bestValidationAccuracy, testAccuracy)
                }
            }
        }
    }


    // source: TFLite examples code
    // Runs one training step with the given bottleneck batches and labels
    // and return the loss number.
    private fun training(
        bottlenecks: MutableList<FloatArray>,
        labels: MutableList<FloatArray>
    ): Float {
        val inputs: MutableMap<String, Any> = HashMap()
        inputs[TRAINING_INPUT_BOTTLENECK_KEY] = bottlenecks.toTypedArray()
        inputs[TRAINING_INPUT_LABELS_KEY] = labels.toTypedArray()

        val outputs: MutableMap<String, Any> = HashMap()
        val loss = FloatBuffer.allocate(1)
        outputs[TRAINING_OUTPUT_KEY] = loss
        Log.d(TAG, "About to call training signature")
        Log.d(TAG, "Bottleneck batch size: ${bottlenecks.size}")
        Log.d(TAG, "Each bottleneck size: ${bottlenecks[0].size}")
        Log.d(TAG, "Labels batch size: ${labels.size}")

        interpreter?.runSignature(inputs, outputs, TRAINING_KEY)
        return loss.get(0)
    }

    private fun calculateValidationAccuracy(): Float {
        if (validationSamples.isEmpty()) return 0.0f

        var correctPredictions = 0

        validationSamples.forEach { sample ->
            val results = classifyWithBottleneck(sample.bottleneck)

            val predictedClass = if (results.isNotEmpty()) {
                classes[results[0].label] ?: -1
            } else -1

            val actualClass = sample.label.indices.maxByOrNull { sample.label[it] } ?: -1

            if (predictedClass == actualClass) {
                correctPredictions++
            }
        }

        return correctPredictions.toFloat() / validationSamples.size
    }

    fun addValidationBottleneck(bottleneck: FloatArray, label: FloatArray) {
        synchronized(lock) {
            validationSamples.add(TrainingSample(bottleneck, label, null))
        }
    }

    fun addTestBottleneck(bottleneck: FloatArray, label: FloatArray) {
        synchronized(lock) {
            testSamples.add(TrainingSample(bottleneck, label, null))
        }
    }

    private fun evaluateTestSet(): Float {
        if (testSamples.isEmpty()) {
            Log.w(TAG, "No test samples to evaluate.")
            return 0.0f
        }

        var correctPredictions = 0
        Log.d(TAG, "Starting evaluation on ${testSamples.size} test samples.")

        testSamples.forEach { sample ->
            val inputs: MutableMap<String, Any> = HashMap()
            inputs[TRAINING_INPUT_BOTTLENECK_KEY] = arrayOf(sample.bottleneck)

            val outputs: MutableMap<String, Any> = HashMap()
            val output = Array(1) { FloatArray(62) }
            outputs[INFERENCE_OUTPUT_KEY] = output

            try {
                interpreter?.runSignature(inputs, outputs, "infer_with_bottleneck")
            } catch (e: Exception) {
                Log.e(TAG, "Error running test inference: ${e.message}")
                return -1.0f
            }

            val predictedClass = output[0].indices.maxByOrNull { output[0][it] } ?: -1
            val actualClass = sample.label.indices.maxByOrNull { sample.label[it] } ?: -1

            if (predictedClass == actualClass) {
                correctPredictions++
            }
        }

        val accuracy = correctPredictions.toFloat() / testSamples.size
        Log.d(TAG, "Test set evaluation complete. Accuracy: $accuracy")
        testSamples.clear()

        return accuracy
    }




    //    private fun calculateValidationAccuracy(): Float {
//        if (validationSamples.isEmpty()) return 0.0f
//
//        var correctPredictions = 0
//
//        validationSamples.forEach { sample ->
//            sample.originalImage?.let { tensorImage ->
//                val inputs: MutableMap<String, Any> = HashMap()
//                inputs[INFERENCE_INPUT_KEY] = tensorImage.buffer
//
//                val outputs: MutableMap<String, Any> = HashMap()
//                val output = Array(1) { FloatArray(62) }
//                outputs[INFERENCE_OUTPUT_KEY] = output
//
//                interpreter?.runSignature(inputs, outputs, INFERENCE_KEY)
//
//                // Get predicted class
//                val predictedClass = output[0].indices.maxByOrNull { output[0][it] } ?: -1
//                val actualClass = sample.label.indices.maxByOrNull { sample.label[it] } ?: -1
////                Log.d(TAG, "Actual: $actualClass, Predicted: $predictedClass")
//                if (predictedClass == actualClass) {
//                    correctPredictions++
//                }
//            }
//        }
//
//        return correctPredictions.toFloat() / validationSamples.size
//    }\



    // source: TFLite examples code
    // Loads the bottleneck feature from the given image array.
    private fun loadBottleneck(image: TensorImage): FloatArray {
        val inputs: MutableMap<String, Any> = HashMap()
        inputs[LOAD_BOTTLENECK_INPUT_KEY] = image.buffer
        val outputs: MutableMap<String, Any> = HashMap()
        val bottleneck = Array(1) { FloatArray(BOTTLENECK_SIZE) }
        outputs[LOAD_BOTTLENECK_OUTPUT_KEY] = bottleneck
        interpreter?.runSignature(inputs, outputs, LOAD_BOTTLENECK_KEY)
        return bottleneck[0]
    }




    // source: TFLite examples code
    // Preprocess the image and convert it into a TensorImage for classification.
    private fun processInputImage(
        image: Bitmap,
        imageRotation: Int
    ): TensorImage? {
        val imageProcessor = ImageProcessor.Builder()
            .add(NormalizeOp(0f, 255f))
            .build()

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(image)
        return imageProcessor.process(tensorImage)
    }



    // source: TFLite examples code
    // encode the classes name to float array
    private fun encoding(id: Int): FloatArray {
        val classEncoded = FloatArray(62) { 0f }
        classEncoded[id] = 1f
        return classEncoded
    }


    // source: TFLite examples code
    // Training model expected batch size.
    private fun getTrainBatchSize(): Int {
        return min(
            max( /* at least one sample needed */1, trainingSamples.size),
            EXPECTED_BATCH_SIZE
        )
    }


    // source: TFLite examples code
    // Constructs an iterator that iterates over training sample batches.
    private fun trainingBatches(trainBatchSize: Int): Iterator<List<TrainingSample>> {
        return object : Iterator<List<TrainingSample>> {
            private var nextIndex = 0

            override fun hasNext(): Boolean {
                return nextIndex < trainingSamples.size
            }

            override fun next(): List<TrainingSample> {
                val fromIndex = nextIndex
                val toIndex: Int = nextIndex + trainBatchSize
                nextIndex = toIndex
                return if (toIndex >= trainingSamples.size) {
                    // To keep batch size consistent, last batch may include some elements from the
                    // next-to-last batch.
                    trainingSamples.subList(
                        trainingSamples.size - trainBatchSize,
                        trainingSamples.size
                    )
                } else {
                    trainingSamples.subList(fromIndex, toIndex)
                }
            }
        }
    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(results: List<Category>?, inferenceTime: Long)
        fun onLossResults(lossNumber: Float)
        fun onEpochCompleted(epoch: Int, avgLoss: Float, validationAccuracy: Float)
        fun onEarlyStopping(epoch: Int, bestValidationAccuracy: Float, reason: String)
        fun onTrainingCompleted(totalEpochs: Int, finalValidationAccuracy: Float, finalTestAccuracy: Float)
    }



    companion object {
        private val classes = mapOf(
            "0" to 0, "1" to 1, "2" to 2, "3" to 3, "4" to 4,
            "5" to 5, "6" to 6, "7" to 7, "8" to 8, "9" to 9,

            "A" to 10, "B" to 11, "C" to 12, "D" to 13, "E" to 14, "F" to 15, "G" to 16, "H" to 17,
            "I" to 18, "J" to 19, "K" to 20, "L" to 21, "M" to 22, "N" to 23, "O" to 24, "P" to 25,
            "Q" to 26, "R" to 27, "S" to 28, "T" to 29, "U" to 30, "V" to 31, "W" to 32, "X" to 33,
            "Y" to 34, "Z" to 35,

            "lowercase_a" to 36, "lowercase_b" to 37, "lowercase_c" to 38, "lowercase_d" to 39, "lowercase_e" to 40,
            "lowercase_f" to 41, "lowercase_g" to 42, "lowercase_h" to 43, "lowercase_i" to 44, "lowercase_j" to 45,
            "lowercase_k" to 46, "lowercase_l" to 47, "lowercase_m" to 48, "lowercase_n" to 49, "lowercase_o" to 50,
            "lowercase_p" to 51, "lowercase_q" to 52, "lowercase_r" to 53, "lowercase_s" to 54, "lowercase_t" to 55,
            "lowercase_u" to 56, "lowercase_v" to 57, "lowercase_w" to 58, "lowercase_x" to 59, "lowercase_y" to 60,
            "lowercase_z" to 61
        )
        private const val LOAD_BOTTLENECK_INPUT_KEY = "feature"
        private const val LOAD_BOTTLENECK_OUTPUT_KEY = "bottleneck"
        private const val LOAD_BOTTLENECK_KEY = "load"

        private const val TRAINING_INPUT_BOTTLENECK_KEY = "bottleneck"
        private const val TRAINING_INPUT_LABELS_KEY = "y"
        private const val TRAINING_OUTPUT_KEY = "loss"
        private const val TRAINING_KEY = "train"

        private const val INFERENCE_INPUT_KEY = "feature"
        private const val INFERENCE_OUTPUT_KEY = "output"
        private const val INFERENCE_KEY = "infer"

        private const val INFERENCE_BOTTLENECK_INPUT_KEY = "bottleneck"
        private const val INFERENCE_BOTTLENECK_OUTPUT_KEY = "output"
        private const val INFERENCE_BOTTLENECK_KEY = "infer_with_bottleneck"

        private const val BOTTLENECK_SIZE = 1 * 7 * 7 * 1280
        private const val EXPECTED_BATCH_SIZE = 16
        private const val TAG = "ModelPersonalizationHelper"
    }

    data class TrainingSample(val bottleneck: FloatArray, val label: FloatArray, val originalImage: TensorImage? = null)
}
