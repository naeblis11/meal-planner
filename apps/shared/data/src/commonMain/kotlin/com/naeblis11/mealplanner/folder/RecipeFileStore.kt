package com.naeblis11.mealplanner.folder

/**
 * The desktop's recipe folder as RecipeRepository writes it. Null on Android, where Room is the
 * source of truth. File names are bare names inside the folder ("soup.yaml").
 */
interface RecipeFileStore {
    /**
     * True when [fileName] is already taken in the folder. The filesystem decides whether letter case
     * matters: on Windows (NTFS) "Soup.yaml" also takes "soup.yaml"; on a case-sensitive one it doesn't.
     */
    fun exists(fileName: String): Boolean

    /** The bytes of [fileName], or null when there is no such file. */
    fun read(fileName: String): ByteArray?

    /** Writes [text] as UTF-8 to [fileName], replacing it atomically, and returns the SHA-256 (hex) of the bytes written. */
    fun write(fileName: String, text: String): String

    /**
     * Puts [fileName] back as [read] found it, to undo a failed save: [bytes] written atomically, or,
     * when they are null, the file deleted for good (the failed save created it, so nothing is lost).
     */
    fun restore(fileName: String, bytes: ByteArray?)

    /** Moves [fileName] to the Recycle Bin, or deletes it where there is none; nothing when it is already gone. */
    fun trash(fileName: String)
}

/** A recipe file as the index records it: its name and the SHA-256 of its bytes. */
data class FileStamp(val name: String, val hash: String)
