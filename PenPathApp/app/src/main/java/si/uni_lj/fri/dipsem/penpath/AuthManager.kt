package si.uni_lj.fri.dipsem.penpath

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AuthManager {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val COOLDOWN_DURATION_MS = 30 * 60 * 1000L
    }

    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    suspend fun signInAnonymously(): Result<String> {
        return try {
            Log.d("AuthManager", "Attempting anonymous sign in...")
            val result = auth.signInAnonymously().await()
            val userId = result.user?.uid ?: throw Exception("Failed to get user ID")
            Log.d("AuthManager", "Anonymous sign in successful. User ID: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e("AuthManager", "Anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun saveUserInitials(userId: String, initials: String) {
        try {
            Log.d("AuthManager", "Saving user initials: $initials for user: $userId")
            val userData = hashMapOf(
                "initials" to initials,
                "createdAt" to System.currentTimeMillis(),
                "isAnonymous" to true
            )

            firestore.collection("users")
                .document(userId)
                .set(userData)
                .await()

            Log.d("AuthManager", "User initials saved successfully")
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to save user initials", e)
            throw e
        }
    }

    suspend fun getUserInitials(userId: String): String? {
        return try {
            Log.d("AuthManager", "Getting user initials for: $userId")
            val document = firestore.collection("users")
                .document(userId)
                .get()
                .await()

            val initials = document.getString("initials")
            Log.d("AuthManager", "Retrieved initials: $initials")
            return initials
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to get user initials", e)
            null
        }
    }

    suspend fun hasInitials(userId: String): Boolean {
        return getUserInitials(userId) != null
    }


    suspend fun initializeLetterSessions(userId: String) {
        try {
            val characters = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            val defaultSessions = characters.associate { char ->
                char.toString() to mapOf(
                    "sessionId" to 3,
                    "counter" to 10
                )
            }

            firestore.collection("users")
                .document(userId)
                .update("letterSessions", defaultSessions)
                .await()

            Log.d("AuthManager", "Initialized letter sessions for user: $userId")
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to initialize letter sessions", e)
            throw e
        }
    }

    fun isLetterInCooldown(session: LetterSession): Boolean {
        if (session.counter > 0|| session.sessionId == 0) return false

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
        if (session.counter == 0 && !isLetterInCooldown(session) && session.sessionId > 0) {
            val newSession = session.copy(
                sessionId = session.sessionId - 1,
                counter = if (session.sessionId - 1 > 0) 10 else 0,
                lastUsedTimestamp = 0L
            )
            return Pair(newSession, true)
        }
        return Pair(session, false)
    }

    suspend fun updateLetterSession(userId: String, letter: Char, session: LetterSession) {
        try {
            val sessionData = mapOf(
                "sessionId" to session.sessionId,
                "counter" to session.counter,
                "lastUsedTimestamp" to session.lastUsedTimestamp
            )

            firestore.collection("users")
                .document(userId)
                .update("letterSessions.${letter}", sessionData)
                .await()

            Log.d("AuthManager", "Updated session for letter $letter: counter=${session.counter}, timestamp=${session.lastUsedTimestamp}")
        } catch (e: Exception) {
            Log.e("AuthManager", "Failed to update letter session", e)
            throw e
        }
    }

    suspend fun getLetterSessions(userId: String): Map<Char, LetterSession>? {
        return try {
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
            Log.e("AuthManager", "Failed to get letter sessions", e)
            null
        }
    }

}