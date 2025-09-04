package si.uni_lj.fri.dipsem.penpath

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

@Singleton
class FirebaseRepository @Inject constructor() : UserRepository {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val httpClient = OkHttpClient()
    private val CLOUDINARY_CLOUD_NAME = "secret"
    private val CLOUDINARY_UPLOAD_PRESET = "penpath_letters"


    companion object {
        private const val COOLDOWN_DURATION_MS = 30 * 60 * 1000L
    }

    override suspend fun signInAnonymously(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d("FirebaseRepository", "Attempting anonymous sign in...")
            val result = auth.signInAnonymously().await()
            val userId = result.user?.uid ?: throw Exception("Failed to get user ID")
            Log.d("FirebaseRepository", "Anonymous sign in successful. User ID: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getUserInitials(userId: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("FirebaseRepository", "Getting user initials for: $userId")
            val document = firestore.collection("users")
                .document(userId)
                .get()
                .await()

            val initials = document.getString("initials")
            Log.d("FirebaseRepository", "Retrieved initials: $initials")
            return@withContext initials
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to get user initials", e)
            null
        }
    }

    override suspend fun saveUserInitials(userId: String, initials: String) = withContext(Dispatchers.IO) {
        try {
            Log.d("FirebaseRepository", "Saving user initials: $initials for user: $userId")
            val userData = hashMapOf(
                "initials" to initials,
                "createdAt" to System.currentTimeMillis(),
                "isAnonymous" to true
            )

            firestore.collection("users")
                .document(userId)
                .set(userData)
                .await()

            Log.d("FirebaseRepository", "User initials saved successfully")
            Unit // Add this explicit Unit return
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to save user initials", e)
            throw e
        }
    }


    override suspend fun getLetterSessions(userId: String): Map<Char, LetterSession>? = withContext(Dispatchers.IO) {
        try {
            val document = firestore.collection("users")
                .document(userId)
                .get()
                .await()

            val sessionsData = document.get("letterSessions") as? Map<String, Map<String, Any>>
            sessionsData?.mapKeys { it.key.first() }
                ?.mapValues { (_, sessionData) ->
                    LetterSession(
                        sessionId = (sessionData["sessionId"] as Long).toInt(),
                        counter = (sessionData["counter"] as Long).toInt(),
                        lastUsedTimestamp = sessionData["lastUsedTimestamp"] as? Long ?: 0L
                    )
                }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to get letter sessions", e)
            null
        }
    }

    override suspend fun initializeLetterSessions(userId: String) = withContext(Dispatchers.IO) {
        try {
            val characters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            val defaultSessions = characters.associate { char ->
                char.toString() to mapOf(
                    "sessionId" to 3,
                    "counter" to 10,
                    "lastUsedTimestamp" to 0L
                )
            }

            firestore.collection("users")
                .document(userId)
                .update("letterSessions", defaultSessions)
                .await()

            Log.d("FirebaseRepository", "Initialized letter sessions for user: $userId")
            Unit
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to initialize letter sessions", e)
            throw e
        }
    }

    override suspend fun updateLetterSession(userId: String, letter: Char, session: LetterSession) = withContext(Dispatchers.IO) {
        try {
            val sessionData = mapOf(
                "sessionId" to session.sessionId,
                "counter" to session.counter,
                "lastUsedTimestamp" to session.lastUsedTimestamp
            )

            firestore.collection("users")
                .document(userId)
                .update("letterSessions.$letter", sessionData)
                .await()

            Log.d("FirebaseRepository", "Updated session for letter $letter: counter=${session.counter}")
            Unit
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to update letter session", e)
            throw e
        }
    }

    // Helper methods for your existing AuthManager logic
    fun isLetterInCooldown(session: LetterSession): Boolean {
        if (session.counter > 0 || session.sessionId == 0) return false
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUse = currentTime - session.lastUsedTimestamp
        return timeSinceLastUse < COOLDOWN_DURATION_MS
    }

    fun getRemainingCooldownMinutes(session: LetterSession): Int {
        if (session.counter > 0 || session.sessionId == 0) return 0
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUse = currentTime - session.lastUsedTimestamp
        val remainingMs = COOLDOWN_DURATION_MS - timeSinceLastUse
        return if (remainingMs > 0) {
            (remainingMs / (60 * 1000)).toInt() + 1
        } else {
            0
        }
    }

    fun isLetterCompletelyFinished(session: LetterSession): Boolean {
        return session.sessionId == 1 && session.counter == 0
    }

    fun resetLetterIfCooldownExpired(session: LetterSession): Pair<LetterSession, Boolean> {
        if (session.counter == 0 && !isLetterInCooldown(session) && session.sessionId > 1) {
            val newSession = session.copy(
                sessionId = session.sessionId - 1,
                counter = 10,
                lastUsedTimestamp = 0L
            )
            return Pair(newSession, true)
        }
        return Pair(session, false)
    }

    suspend fun uploadDrawingToCloudinary(
        selectedLetter: Char,
        userInitials: String,
        sessionId: Int,
        counter: Int,
        imageBytes: ByteArray
    ): String? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "${selectedLetter}_${userInitials}_s${sessionId}_c${counter}_${timestamp}"
            val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)


            val requestBody = FormBody.Builder()
                .add("file", "data:image/jpeg;base64,$base64Image")
                .add("upload_preset", CLOUDINARY_UPLOAD_PRESET)
                .add("public_id", filename)
                .add("folder", "penpath_drawings/$userInitials")
                .add("resource_type", "image")
//                .add("format", "jpg")
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/image/upload")
                .post(requestBody)
                .build()

            Log.d("FirebaseRepository", "Uploading image TO CLOUDINARY: $filename.jpg")

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                val imageUrl = jsonResponse.getString("secure_url")
                val publicId = jsonResponse.getString("public_id")

                Log.d("FirebaseRepository", "Image uploaded successfully: $filename.jpg")
                Log.d("FirebaseRepository", "URL: $imageUrl")

                return@withContext imageUrl
            } else {
                Log.e("FirebaseRepository", "Upload failed with code: ${response.code}")
                Log.e("FirebaseRepository", "Response: ${response.body?.string()}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error uploading to Cloudinary", e)
            return@withContext null
        }
    }

    suspend fun saveDrawingInfo(
        userId: String,
        userInitials: String,
        letterLabel: Char,
        sessionId: Int,
        counter: Int,
        imageUrl: String
    ) = withContext(Dispatchers.IO) {
        try {
            val drawingData = mapOf(
                "userId" to userId,
                "userInitials" to userInitials,
                "letterLabel" to letterLabel.toString(),
                "sessionId" to sessionId,
                "counter" to counter,
                "imageUrl" to imageUrl,
                "timestamp" to System.currentTimeMillis(),

            )

            firestore.collection("drawings")
                .add(drawingData)
                .await()

            Log.d("FirebaseRepository", "📱 Drawing info saved to Firestore")
            Unit
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Failed to save drawing info", e)
            throw e
        }
    }
}
