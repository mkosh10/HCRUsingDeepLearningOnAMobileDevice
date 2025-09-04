package si.uni_lj.fri.dipsem.penpath

import android.app.appsearch.ReportSystemUsageRequest
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import si.uni_lj.fri.dipsem.penpath.ui.UserSetupScreen
import si.uni_lj.fri.dipsem.penpath.ui.theme.PenPathTheme

val LightPurple = Color(0xFFFBEEFF)
val LightGreen = Color(0xFFCDE8B1)
val DarkBlue = Color(0xFF306F92)
val LightOrange = Color(0xFFF17C5D)
val DarkGreen = Color(0xFF2D8078)
val DarkOrange = Color(0xFFCD4F41)
val BackgroundColor2 = Color(0xFFFFEDE1)

val PastelGreen = Color(0xFF8DAAA6)
val PastelLightPink = Color(0xFFF6D5B6)
val PastelMediumPink = Color(0xFFE9AE8C)
val PastelDarkPink = Color(0xFFFF9375)

val BackgroundColor = Color(0xFFFFFAE0)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PenPathTheme {
                var userInfo by remember {  mutableStateOf<Triple<String, String, Map<Char, LetterSession>>?>(null)  }

                if (userInfo == null) {
                    UserSetupScreen(
                        onSetupComplete = { userId, initials, sessions ->
                            userInfo = Triple(userId, initials, sessions)
                        }
                    )
                } else {
                    val (userId, initials, initialsessions) = userInfo!!
                    Main(userId, initials, initialsessions)

            }
        }
    }
}

@Composable
fun Main(userId: String, initials: String, initialSessions: Map<Char, LetterSession>) {
    val viewModel = viewModel<DrawViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repository = remember { FirebaseRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedLetter by remember { mutableStateOf('A') }

    val letterSessions = remember { mutableStateOf(initialSessions) }

    fun checkAndResetCooldowns() {
        letterSessions.value = letterSessions.value.mapValues { (letter, session) ->
            val (resetSession, wasSessionDecremented) = repository.resetLetterIfCooldownExpired(session)

            if (wasSessionDecremented) {
                if (resetSession.sessionId == 0) {
                    Toast.makeText(
                        context,
                        "Writing letter '$letter' is finished!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Session completed for letter '$letter'! ${resetSession.sessionId} sessions remaining.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                scope.launch {
                    repository.updateLetterSession(userId, letter, resetSession)
                }
            }
            resetSession
        }
    }

    LaunchedEffect(userId) {
        checkAndResetCooldowns()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val characterCounts = letterSessions.value.mapValues { it.value.counter }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopPart(userId, initials)
            LetterBox(
                selectedLetter = selectedLetter.toString(),
                characterCounts = characterCounts,
                letterSessions = letterSessions.value,
                repository = repository,
                context = context,
                onLetterSelected = { letter ->
                    selectedLetter = letter.first()
                    val session = letterSessions.value[selectedLetter]

                    if (session != null) {
                        when {
                            repository.isLetterCompletelyFinished(session) -> {
                                Toast.makeText(
                                    context,
                                    "Letter '$selectedLetter' practice is completely finished!",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@LetterBox
                            }
                            session.counter == 0 && repository.isLetterInCooldown(session) -> {
                                val remainingMinutes = repository.getRemainingCooldownMinutes(session)
                                Toast.makeText(
                                    context,
                                    "Letter '$selectedLetter' is on cooldown! $remainingMinutes minutes remaining.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@LetterBox
                            }
                            session.counter == 0 && !repository.isLetterInCooldown(session) -> {
                                val (resetSession, wasDecremented) = repository.resetLetterIfCooldownExpired(session)
                                if (wasDecremented) {
                                    letterSessions.value = letterSessions.value.toMutableMap().apply {
                                        put(selectedLetter, resetSession)
                                    }
                                    scope.launch {
                                        repository.updateLetterSession(userId, selectedLetter, resetSession)
                                    }

                                    if (resetSession.sessionId == 0) {
                                        Toast.makeText(
                                            context,
                                            "🎉 Writing letter '$selectedLetter' is finished! You've completed all practice sessions.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@LetterBox
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Session completed for letter '$selectedLetter'! ${resetSession.sessionId} sessions remaining.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }

                    viewModel.onAction(DrawingAction.OnClearCanvasClick)
                }
            )
            SelectedLetter(selectedLetter.toString())
            DrawingCanvas(
                paths = state.paths,
                currentPath = state.currentPath,
                onAction = viewModel::onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            PenPathButtons(
                viewModel = viewModel,
                selectedLetter = selectedLetter,
                context = context,
                repository = repository,
                userId = userId,
                initials = initials,
                letterSessions = letterSessions,
                scope = scope
            )
        }
    }
}


@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PenPathTheme {
        Greeting("Android")
    }
}

@Composable
fun PenPathMain(){

}

@Composable
fun TopPart(idNum: String, initials : String){
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,){
        Button(onClick = {},
            modifier = Modifier
                .padding(2.dp)
                .size(37.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkGreen
            ),
        ){

            Image(
                painter = painterResource(id = R.drawable.new_ic_green),
                contentDescription = "Emoji",
                modifier = Modifier.size(27.dp)
            )
        }
        Text(modifier = Modifier.padding(end=10.dp),
            text = "Hi, $initials! ID: ${idNum.takeLast(3)}")
    }
}

@Composable
fun LetterBox(selectedLetter: String,
              characterCounts: Map<Char, Int>,
              repository: FirebaseRepository,
              context : Context,
              letterSessions: Map<Char, LetterSession>,
              onLetterSelected: (String) -> Unit) {
    val characters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = gridState,
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(15.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(characters.size) { index ->
            val character = characters[index]
            val isSelected = character.toString() == selectedLetter
            val remainingCount = characterCounts[character] ?: 0
            val session = letterSessions[character]
            val isInCooldown = session?.let { repository.isLetterInCooldown(it) } ?: false
            val isCompletelyFinished = session?.let { repository.isLetterCompletelyFinished(it) } ?: false
            Box(
                modifier = Modifier
                    .width(57.dp)
                    .height(57.dp)
                    .shadow(3.dp, shape = RoundedCornerShape(15.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .clickable {
                        onLetterSelected(character.toString())
                    },


                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                isCompletelyFinished -> Color(0xFF4CAF50)
                                isInCooldown -> Color.Gray
                                isSelected -> LightGreen
                                else -> PastelMediumPink
                        }),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = characters[index].toString(),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp
                    )

                    Column(modifier = Modifier.fillMaxHeight().padding(top = 2.dp, bottom=2.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.End) {

                        Text(
                            modifier = Modifier.padding(end = 3.dp),
                            text = if (isCompletelyFinished) "0" else letterSessions[character]?.sessionId?.toString() ?: "3",
                            textAlign = TextAlign.End,
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        if(isCompletelyFinished){
                            Text(
                                modifier = Modifier.padding(end = 3.dp),
                                text = "✔",
                                textAlign = TextAlign.End,
                                fontSize = 10.sp,
                                color = Color.White
                            )
                        }

                        Text(
                            modifier = Modifier.padding(end = 3.dp),
                            text = remainingCount.toString(),
                            textAlign = TextAlign.End,
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
//                    Text(
//                        modifier = Modifier.padding(top = 15.dp, end = 3.dp),
//                        text = remainingCount.toString(),
//                        textAlign = TextAlign.End,
//                        fontSize = 12.sp,
//                        color = Color.Black
//                    )
                }
            }
        }



    }


}

@Composable
fun SelectedLetter(letter: String) {
    Text(
        text = "Selected character to draw: $letter",
        textAlign = TextAlign.Start,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
    )
}


@Composable
fun PenPathButtons(
    viewModel: DrawViewModel,
    selectedLetter: Char,
    context: Context,
    repository: FirebaseRepository,
    userId: String,
    initials: String,
    letterSessions: MutableState<Map<Char, LetterSession>>,
    scope: CoroutineScope
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Button(
            onClick = { viewModel.onAction(DrawingAction.OnClearCanvasClick) },
            modifier = Modifier
                .padding(top = 16.dp)
                .width(140.dp)
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(30.dp)),
            colors = ButtonDefaults.buttonColors(
                containerColor = LightOrange
            )
        ) {
            Text(text = "Clear", textAlign = TextAlign.Center)
        }

        Button(
            onClick = {
                submitLetter(
                    viewModel = viewModel,
                    selectedLetter = selectedLetter,
                    context = context,
                    repository = repository,
                    userId = userId,
                    initials = initials,
                    letterSessions = letterSessions,
                    scope = scope
                )
            },
            modifier = Modifier
                .padding(top = 16.dp)
                .width(130.dp)
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(30.dp)),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkGreen
            ),
            shape = RoundedCornerShape(30.dp)
        ) {
            Text(text = "Submit", textAlign = TextAlign.Center)
        }
    }
}



fun submitLetter(
    viewModel: DrawViewModel,
    selectedLetter: Char,
    context: Context,
    repository: FirebaseRepository,
    userId: String,
    initials: String,
    letterSessions: MutableState<Map<Char, LetterSession>>,
    scope: CoroutineScope
) {
    val session = letterSessions.value[selectedLetter]

    if (session == null) {
        Toast.makeText(context, "Session not found for letter '$selectedLetter'", Toast.LENGTH_SHORT).show()
        return
    }

    when {
        repository.isLetterCompletelyFinished(session) -> {
            Toast.makeText(
                context,
                "Drawing letter '$selectedLetter' is completely finished! Thank you!",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        session.counter == 0 && repository.isLetterInCooldown(session) -> {
            val remainingMinutes = repository.getRemainingCooldownMinutes(session)
            Toast.makeText(
                context,
                "Letter '$selectedLetter' is on cooldown! $remainingMinutes minutes remaining.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
    }

    scope.launch {
        try {
            Log.d("SubmitLetter", "Attempting to capture canvas for letter: $selectedLetter")
            val imageBytes = viewModel.captureCanvasAs224Square()

            if (imageBytes == null) {
                Log.w("SubmitLetter", "Canvas capture failed - no valid paths found")
                Toast.makeText(
                    context,
                    "Please draw the letter '$selectedLetter' before submitting!",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Log.d("SubmitLetter", "Canvas captured successfully (${imageBytes.size} bytes), clearing canvas")
            viewModel.onAction(DrawingAction.OnClearCanvasClick)

            Toast.makeText(context, "Submitted!", Toast.LENGTH_SHORT).show()

            val imageUrl = repository.uploadDrawingToCloudinary(
                selectedLetter = selectedLetter,
                userInitials = initials,
                sessionId = session.sessionId,
                counter = session.counter,
                imageBytes = imageBytes
            )

            if (imageUrl == null) {
                Toast.makeText(
                    context,
                    "Failed to upload drawing. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            repository.saveDrawingInfo(
                userId = userId,
                userInitials = initials,
                letterLabel = selectedLetter,
                sessionId = session.sessionId,
                counter = session.counter,
                imageUrl = imageUrl
            )

            val updatedSession = session.copy(
                counter = session.counter - 1,
                lastUsedTimestamp = if (session.counter == 1) System.currentTimeMillis() else session.lastUsedTimestamp
            )

            repository.updateLetterSession(userId, selectedLetter, updatedSession)

            letterSessions.value = letterSessions.value.toMutableMap().apply {
                put(selectedLetter, updatedSession)
            }

            if (updatedSession.sessionId == 1 && updatedSession.counter == 0) {
                Toast.makeText(
                    context,
                    "Drawing letter '$selectedLetter' is finished!",
                    Toast.LENGTH_LONG
                ).show()
            }

            Log.d("SubmitLetter", "Letter submission completed successfully")

        } catch (e: Exception) {
            Log.e("SubmitLetter", "Error during submission", e)
            Toast.makeText(
                context,
                "An error occurred. Please try again.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}





@Composable
fun DrawingCanvas(
    paths: List<PathData>,
    currentPath: PathData?,
    onAction: (DrawingAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .padding(25.dp)
        .shadow(elevation = 7.dp, shape = RoundedCornerShape(12.dp))
        .border(
            width = 2.dp,
            color = DarkGreen,
            shape = RoundedCornerShape(12.dp)
        )) {
        Canvas(
            modifier = modifier
                .clipToBounds()
                .background(Color.White)
                .pointerInput(true) {
                    detectDragGestures(
                        onDragStart = {
                            onAction(DrawingAction.OnNewPathStart)
                        },
                        onDragEnd = {
                            onAction(DrawingAction.OnPathEnd)
                        },
                        onDrag = { change, _ ->
                            onAction(DrawingAction.OnDraw(change.position))
                        },
                        onDragCancel = {
                            onAction(DrawingAction.OnPathEnd)
                        },
                    )
                }
        ) {
            paths.fastForEach { pathData ->
                drawPath(
                    path = pathData.path,
                    color = pathData.color,
                )
            }
            currentPath?.let {
                drawPath(
                    path = it.path,
                    color = it.color
                )
            }
        }
    }
}

private fun DrawScope.drawPath(
    path: List<Offset>,
    color: Color,
    thickness: Float = 20f
) {
    val smoothedPath = Path().apply {
        if(path.isNotEmpty()) {
            moveTo(path.first().x, path.first().y)

            val smoothness = 5
            for(i in 1..path.lastIndex) {
                val from = path[i - 1]
                val to = path[i]
                val dx = abs(from.x - to.x)
                val dy = abs(from.y - to.y)
                if(dx >= smoothness || dy >= smoothness) {
                    quadraticTo(
                        x1 = (from.x + to.x) / 2f,
                        y1 = (from.y + to.y) / 2f,
                        x2 = to.x,
                        y2 = to.y
                    )
                }
            }
        }
    }
    drawPath(
        path = smoothedPath,
        color = color,
        style = Stroke(
            width = thickness,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}


@Preview
@Composable
fun DrawingCanvasPreview() {
    PenPathTheme {
        var selectedLetter by remember { mutableStateOf("M") }
        val characters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        // Remove: val context = LocalContext.current

        var characterCounts by remember {
            mutableStateOf(characters.associateWith { 10 })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(35.dp)
                .background(BackgroundColor2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopPart("1", "MK")

            // Create preview-safe version
            LetterBoxPreview(
                selectedLetter = selectedLetter,
                characterCounts = characterCounts,
                letterSessions = characters.associateWith {
                    LetterSession(sessionId = 3, counter = 10)
                },
                onLetterSelected = { letter ->
                    selectedLetter = letter
                }
            )

            SelectedLetter(selectedLetter)

            DrawingCanvas(
                paths = listOf(
                    PathData(
                        id = "1",
                        color = Color.Red,
                        path = listOf(Offset(200f, 100f), Offset(200f, 200f))
                    )
                ),
                currentPath = null,
                onAction = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Create preview-safe version
            PenPathButtonsPreview(
                selectedLetter = selectedLetter.first(),
                characterCounts = characterCounts,
                onSubmit = { letter ->
                    characterCounts = characterCounts.toMutableMap().apply {
                        val currentCount = this[letter] ?: 0
                        if (currentCount > 0) {
                            this[letter] = currentCount - 1
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun LetterBoxPreview(
    selectedLetter: String,
    characterCounts: Map<Char, Int>,
    letterSessions: Map<Char, LetterSession>,
    onLetterSelected: (String) -> Unit
) {
    val characters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = gridState,
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(15.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(characters.size) { index ->
            val character = characters[index]
            val isSelected = character.toString() == selectedLetter
            val remainingCount = characterCounts[character] ?: 0
            val session = letterSessions[character]

            Box(
                modifier = Modifier
                    .width(57.dp)
                    .height(57.dp)
                    .shadow(3.dp, shape = RoundedCornerShape(15.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onLetterSelected(character.toString()) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(when {
                            remainingCount == 0 -> Color.Gray
                            isSelected -> LightGreen
                            else -> PastelMediumPink
                        }),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = character.toString(),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp
                    )

                    Column(
                        modifier = Modifier.fillMaxHeight().padding(top = 2.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            modifier = Modifier.padding(end = 3.dp),
                            text = "${session?.sessionId ?: 3}",
                            textAlign = TextAlign.End,
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        Text(
                            modifier = Modifier.padding(end = 3.dp),
                            text = "✔\uFE0F",
                            textAlign = TextAlign.End,
                            fontSize = 7.sp,
                            color = Color.White

                        )
                        Text(
                            modifier = Modifier.padding(end = 3.dp),
                            text = remainingCount.toString(),
                            textAlign = TextAlign.End,
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PenPathButtonsPreview(
    selectedLetter: Char,
    characterCounts: Map<Char, Int>,
    onSubmit: (Char) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Button(
            onClick = { /* Preview mock */ },
            modifier = Modifier
                .padding(top = 16.dp)
                .width(140.dp)
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(30.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = LightOrange)
        ) {
            Text(text = "Clear", textAlign = TextAlign.Center)
        }

        Button(
            onClick = {
                // Preview-safe submit logic
                val remainingCount = characterCounts[selectedLetter] ?: 0
                if (remainingCount > 0) {
                    onSubmit(selectedLetter)
                }
            },
            modifier = Modifier
                .padding(top = 16.dp)
                .width(130.dp)
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(30.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = DarkGreen),
            shape = RoundedCornerShape(30.dp)
        ) {
            Text(text = "Submit", textAlign = TextAlign.Center)
        }
    }


}
}