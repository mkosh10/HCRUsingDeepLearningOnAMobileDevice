package si.uni_lj.fri.dipsem.penpath


import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DrawingState(
    val selectedColor: Color = Color.Black,
    val currentPath: PathData? = null,
    val paths: List<PathData> = emptyList()
)

data class PathData(
    val id: String,
    val color: Color,
    val path: List<Offset>
)

sealed interface DrawingAction {
    data object OnNewPathStart: DrawingAction
    data class OnDraw(val offset: Offset): DrawingAction
    data object OnPathEnd: DrawingAction
    data object OnClearCanvasClick: DrawingAction
}

//# source https://github.com/philipplackner/DrawingInJetpackCompose/tree/master
class DrawViewModel: ViewModel() {

    private val _state = MutableStateFlow(DrawingState())
    val state = _state.asStateFlow()

    fun hasCanvasContent(): Boolean {
        val currentState = state.value

        val allPaths = state.value.paths + listOfNotNull(state.value.currentPath)
        val hasContent = allPaths.isNotEmpty() && allPaths.any { it.path.isNotEmpty() }

        Log.d("DrawViewModel", "Canvas content check:")
        Log.d("DrawViewModel", "- Total paths: ${allPaths.size}")
        Log.d("DrawViewModel", "- Has content: $hasContent")
        return currentState.paths.isNotEmpty() ||
            (currentState.currentPath?.path?.isNotEmpty() == true)
    }

    fun onAction(action: DrawingAction) {
        when(action) {
            DrawingAction.OnClearCanvasClick -> onClearCanvasClick()
            is DrawingAction.OnDraw -> onDraw(action.offset)
            DrawingAction.OnNewPathStart -> onNewPathStart()
            DrawingAction.OnPathEnd -> onPathEnd()
        }
    }


    private fun onPathEnd() {
        val currentPathData = state.value.currentPath ?: return
        _state.update { it.copy(
            currentPath = null,
            paths = it.paths + currentPathData
        ) }
    }

    private fun onNewPathStart() {
        _state.update { it.copy(
            currentPath = PathData(
                id = System.currentTimeMillis().toString(),
                color = it.selectedColor,
                path = emptyList()
            )
        ) }
    }

    private fun onDraw(offset: Offset) {
        val currentPathData = state.value.currentPath ?: return
        _state.update { it.copy(
            currentPath = currentPathData.copy(
                path = currentPathData.path + offset
            )
        ) }
    }

    private fun onClearCanvasClick() {
        _state.update { it.copy(
            currentPath = null,
            paths = emptyList()
        ) }
    }

    fun captureCanvasAs224Square(): ByteArray? {
        try {
            val allPaths = state.value.paths + listOfNotNull(state.value.currentPath)

            Log.d("DrawViewModel", "=== PATH DEBUGGING ===")
            Log.d("DrawViewModel", "Total paths: ${allPaths.size}")

            // Detailed path debugging
            allPaths.forEachIndexed { index, pathData ->
                Log.d("DrawViewModel", "Path $index: ${pathData.path.size} points")
                Log.d("DrawViewModel", "Path $index isEmpty: ${pathData.path.isEmpty()}")
                if (pathData.path.isNotEmpty()) {
                    Log.d("DrawViewModel", "Path $index first point: ${pathData.path.first()}")
                    Log.d("DrawViewModel", "Path $index last point: ${pathData.path.last()}")
                }
            }

            val validPaths = allPaths.filter { it.path.isNotEmpty() }
            Log.d("DrawViewModel", "Valid paths (non-empty): ${validPaths.size}")

            if (validPaths.isEmpty()) {
                Log.w("DrawViewModel", "No valid paths to capture")
                return null
            }

            // Calculate raw bounding box using validPaths
            val rawBounds = calculateRawBoundingBox(validPaths) ?: return null

            val drawingWidth = rawBounds.width()
            val drawingHeight = rawBounds.height()

            Log.d("DrawViewModel", "Raw drawing: ${drawingWidth}×${drawingHeight}")

            // Adaptive padding based on drawing size
            val adaptivePadding = when {
                max(drawingWidth, drawingHeight) < 50 -> 30f   // Small drawing = more padding
                max(drawingWidth, drawingHeight) < 150 -> 20f  // Medium drawing = normal padding
                else -> 15f                                     // Large drawing = less padding
            }

            // Apply padding to get final bounds
            val boundingBox = RectF(
                maxOf(0f, rawBounds.left - adaptivePadding),
                maxOf(0f, rawBounds.top - adaptivePadding),
                rawBounds.right + adaptivePadding,
                rawBounds.bottom + adaptivePadding
            )

            // Find the largest dimension and create square
            val maxDimension = max(boundingBox.width(), boundingBox.height())

            Log.d("DrawViewModel", "Square dimension: ${maxDimension}×${maxDimension}")

            // Center the square on the drawing
            val centerX = boundingBox.centerX()
            val centerY = boundingBox.centerY()
            val halfDim = maxDimension / 2f

            val squareBounds = RectF(
                centerX - halfDim,
                centerY - halfDim,
                centerX + halfDim,
                centerY + halfDim
            )

            // Create 224×224 bitmap
            val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // Scale to fit 224×224
            val scale = 224f / maxDimension

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                strokeWidth = max(2f, 8f * scale) // Adaptive stroke width
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }

            validPaths.forEach { pathData ->
                paint.color = pathData.color.toArgb()
                val androidPath = android.graphics.Path()

                if (pathData.path.isNotEmpty()) {
                    // Transform coordinates to fit in the square
                    val firstPoint = pathData.path.first()
                    val scaledX = (firstPoint.x - squareBounds.left) * scale
                    val scaledY = (firstPoint.y - squareBounds.top) * scale
                    androidPath.moveTo(scaledX, scaledY)

                    pathData.path.drop(1).forEach { offset ->
                        val scaledX = (offset.x - squareBounds.left) * scale
                        val scaledY = (offset.y - squareBounds.top) * scale
                        androidPath.lineTo(scaledX, scaledY)
                    }

                    canvas.drawPath(androidPath, paint)
                }
            }

            // Convert to JPEG
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)

            Log.d("DrawViewModel", "Generated 224×224 image with ${adaptivePadding}px padding")
            return byteArrayOutputStream.toByteArray()

        } catch (e: Exception) {
            Log.e("DrawViewModel", "Failed to capture canvas", e)
            return null
        }
    }

    private fun calculateRawBoundingBox(paths: List<PathData>): RectF? {
        val validPaths = paths.filter { it.path.isNotEmpty() }
        if (validPaths.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        validPaths.forEach { pathData ->
            pathData.path.forEach { offset ->
                minX = min(minX, offset.x)
                minY = min(minY, offset.y)
                maxX = max(maxX, offset.x)
                maxY = max(maxY, offset.y)
            }
        }

        return RectF(minX, minY, maxX, maxY)
    }


}