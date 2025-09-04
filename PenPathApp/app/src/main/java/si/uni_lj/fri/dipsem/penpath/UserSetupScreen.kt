package si.uni_lj.fri.dipsem.penpath.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import si.uni_lj.fri.dipsem.penpath.AuthManager
import si.uni_lj.fri.dipsem.penpath.FirebaseRepository
import si.uni_lj.fri.dipsem.penpath.LetterSession

@Composable
fun UserSetupScreen(
    onSetupComplete: (String, String, Map<Char, LetterSession>) -> Unit
) {
    val repository = remember { FirebaseRepository() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var initials by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var userId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Starting...") }


    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            status = "Connecting to Firebase..."

            repository.signInAnonymously()
                .onSuccess { id ->
                    userId = id
                    status = "Connected! User ID: ${id.take(8)}..."

                    val existingInitials = repository.getUserInitials(id)
                    if (existingInitials != null) {
                        status = "Loading your sessions..."
                        val existingSessions = repository.getLetterSessions(id) ?: emptyMap()

                        status = "Loading..."
                        delay(1000)

                        Toast.makeText(context, "Welcome back, $existingInitials!", Toast.LENGTH_SHORT).show()
                        onSetupComplete(id, existingInitials, existingSessions)
                    } else {
                        status = "Ready for setup"
                    }
                    isLoading = false
                }
                .onFailure { exception ->
                    error = "Connection failed: ${exception.message}"
                    status = "Connection failed"
                    isLoading = false
                }

        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to PenPath!",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = if (error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (!isLoading && userId != null && error == null) {
            Text(
                text = "Please enter your initials to get started",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = initials,
                onValueChange = {
                    if (it.length <= 3) {
                        initials = it.uppercase()
                    }
                },
                label = { Text("Your Initials") },
                placeholder = { Text("e.g., AB") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        status = "Saving your data..."
                        try {
                            repository.saveUserInitials(userId!!, initials)
                            repository.initializeLetterSessions(userId!!)
                            val sessions = repository.getLetterSessions(userId!!) ?: emptyMap()

                            status = "Preparing workspace..."
                            delay(1000)

                            Toast.makeText(context, "Welcome ${initials}!", Toast.LENGTH_SHORT).show()
                            onSetupComplete(userId!!, initials, sessions)
                        } catch (e: Exception) {
                            error = "Failed to save: ${e.message}"
                            status = "Save failed"
                            isLoading = false
                        }
                    }
                },
                enabled = initials.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Start Drawing!")
                }
            }
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }

        error?.let { errorMessage ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
