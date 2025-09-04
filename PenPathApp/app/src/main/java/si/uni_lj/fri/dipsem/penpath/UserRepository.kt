package si.uni_lj.fri.dipsem.penpath

interface UserRepository {
    suspend fun signInAnonymously(): Result<String>
    suspend fun getUserInitials(userId: String): String?
    suspend fun saveUserInitials(userId: String, initials: String)
    suspend fun getLetterSessions(userId: String): Map<Char, LetterSession>?
    suspend fun initializeLetterSessions(userId: String)
    suspend fun updateLetterSession(userId: String, letter: Char, session: LetterSession)
}
