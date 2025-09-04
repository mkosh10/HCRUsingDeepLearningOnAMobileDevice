package si.uni_lj.fri.dipsem.penpath

data class User(
    val initials: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isAnonymous: Boolean = true,
)
