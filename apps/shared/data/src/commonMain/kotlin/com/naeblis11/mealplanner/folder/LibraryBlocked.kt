package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/**
 * What the desktop says when Windows' Controlled folder access keeps it from saving to the library (P7-R10): in the
 * window's banner, in Settings, and in place of a recipe save's error. Only for a write [isLibraryBlocked] judges
 * refused (P7-R10b, P7-R11b); any other failed write says [librarySaveFailedMessage].
 */
const val LIBRARY_BLOCKED_MESSAGE =
    "Windows blocked Meal Planner from saving to Documents\\Meal Planner (Controlled folder access). To fix it: " +
        "Windows Security > Virus & threat protection > Ransomware protection > Allow an app through Controlled " +
        "folder access > add Meal Planner."

/** A write to the library that failed; its message is what the user is shown, with no path but the folder's name. */
open class LibraryWriteException(message: String, cause: Throwable?) : IOException(message, cause)

/** A write to the library that Windows refused; its message is [LIBRARY_BLOCKED_MESSAGE]. */
class LibraryBlockedException(cause: Throwable? = null) : LibraryWriteException(LIBRARY_BLOCKED_MESSAGE, cause)

/** A write to the library that failed for any other reason (a full disk, say); see [librarySaveFailedMessage]. */
class LibrarySaveException(cause: IOException) : LibraryWriteException(librarySaveFailedMessage(cause), cause)

/**
 * A replace, rename or delete of a file that is already there, refused (P7-R10b M3): a lock (OneDrive, a virus
 * scanner, an editor) or a read-only file, never Controlled folder access, which refuses new files and folders.
 */
class ExistingFileRefusedException(cause: IOException) : IOException(OPEN_OR_READ_ONLY, cause)

private const val OPEN_OR_READ_ONLY = "the file may be open or read-only"

/**
 * The one classifier for a failed write to the library (P7-R11b): true when Controlled folder access refused it.
 * Every library write (the first run's folders, the sync's first folder, Check again, a recipe save, a photo save, an
 * import) is judged here, so they all agree.
 *
 * - NIO's AccessDeniedException, or java.io's IOException (File.createTempFile, FileOutputStream) whose message says
 *   "Access is denied", here or as the cause of a wrapping IOException (the photo writer's "Could not save <name>.").
 * - NoSuchFileException, or java.io's "The system cannot find the path (or file) specified": what the owner's PC
 *   really gave for a folder the block refused (Defender event 1123 at the same moment). It is the block only when
 *   the path that failed has a parent that is an existing folder, so a create that failed only because a folder
 *   above it is genuinely missing (an offline drive) stays the generic failure. Files.createDirectories makes each
 *   missing level in turn and fails on the level it couldn't make, so its failure names that level. The path is the
 *   exception's own, else the one java.io puts before "(reason)", else [target] (the file or folder being made).
 *   That look is a read: nothing is ever written only to look (P7-R10b).
 *
 * Never the block: a refused replace, rename or delete of an existing file (ExistingFileRefusedException), or a
 * failure already judged otherwise (LibrarySaveException).
 */
fun isLibraryBlocked(e: Throwable, target: File? = null): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth++ < 5) {
        when {
            current is ExistingFileRefusedException || current is LibrarySaveException -> return false
            current is LibraryBlockedException -> return true
            current is AccessDeniedException -> return true
            current is NoSuchFileException -> return parentIsAFolder(failedPath(current) ?: target)
            current is IOException && says(current, ACCESS_DENIED) -> return true
            // java.io's words; NIO's own kinds (a FileSystemException) are judged by kind above, never by words.
            current is IOException && current !is FileSystemException && NOT_FOUND.any { says(current, it) } ->
                return parentIsAFolder(failedPath(current) ?: target)
        }
        current = current.cause
    }
    return false
}

private const val ACCESS_DENIED = "Access is denied"
private val NOT_FOUND = listOf("cannot find the path specified", "cannot find the file specified")

private fun says(e: Throwable, text: String): Boolean = e.message?.contains(text, ignoreCase = true) == true

// The path a failure names: NIO's file, else java.io's "<path> (<reason>)"; null when it names none.
private fun failedPath(e: Throwable): File? {
    if (e is FileSystemException) return e.file?.takeIf { it.isNotBlank() }?.let(::File)
    val message = e.message?.trim() ?: return null
    val path = Regex("""^(.+?)\s+\([^()]+\)$""").find(message)?.groupValues?.get(1)?.trim() ?: return null
    return if (namesAPath(path)) File(path) else null
}

// A read only: the folder the failed path would have gone in is there.
private fun parentIsAFolder(path: File?): Boolean = path?.absoluteFile?.parentFile?.isDirectory == true

/**
 * What a failed library write turns into: LibraryBlockedException when [isLibraryBlocked], else LibrarySaveException
 * (an exception already one of the two is kept as it is).
 */
fun libraryWriteFailure(e: IOException, target: File? = null): LibraryWriteException = when {
    e is LibraryWriteException -> e
    isLibraryBlocked(e, target) -> LibraryBlockedException(e)
    else -> LibrarySaveException(e)
}

/** What the window's notice says for a failed library write: LIBRARY_BLOCKED_MESSAGE, else [librarySaveFailedMessage]. */
fun libraryNoticeFor(e: IOException, target: File? = null): String = when {
    isLibraryBlocked(e, target) -> LIBRARY_BLOCKED_MESSAGE
    e is LibraryWriteException -> e.message.orEmpty()
    else -> librarySaveFailedMessage(e)
}

/**
 * "Couldn't save to Documents\Meal Planner: <reason>", the reason without any path: a FileSystemException's reason,
 * the "(reason)" java.io puts after a path, a message that names no path, or else only the failure's kind, said in
 * words where NIO gives no reason (NoSuchFileException: "Windows couldn't find part of the path").
 */
fun librarySaveFailedMessage(e: IOException): String {
    if (generateSequence<Throwable>(e) { it.cause }.take(5).any { it is ExistingFileRefusedException }) {
        return "Couldn't save to Documents\\Meal Planner: $OPEN_OR_READ_ONLY"
    }
    val message = e.message?.trim().orEmpty()
    val inParens = Regex("""\(([^()]+)\)\s*$""").find(message)?.groupValues?.get(1)?.trim()
    val reason = when {
        e is FileSystemException -> e.reason?.trim()?.takeIf { it.isNotEmpty() && !namesAPath(it) }
        inParens != null && !namesAPath(inParens) -> inParens
        message.isNotEmpty() && !namesAPath(message) -> message
        else -> null
    } ?: readableKind(e)
    return "Couldn't save to Documents\\Meal Planner: $reason"
}

/**
 * A first run's library folder [name] that couldn't be made (M7): a file of that name in the way is said plainly;
 * anything else as [librarySaveFailedMessage]. Controlled folder access is the caller's to tell first.
 */
fun libraryFolderFailedMessage(name: String, e: IOException): String =
    if (e is FileAlreadyExistsException) {
        "Couldn't make the $name folder in Documents\\Meal Planner: a file called $name is in the way. " +
            "Move or rename it, then choose Check again."
    } else {
        librarySaveFailedMessage(e)
    }

// NIO's common failures carry no reason of their own on Windows; said in words rather than as a class name.
private fun readableKind(e: IOException): String = when (e) {
    is NoSuchFileException -> "Windows couldn't find part of the path"
    is AccessDeniedException -> "Windows refused access"
    is FileAlreadyExistsException -> "something with that name is already there"
    is NotDirectoryException -> "part of the path isn't a folder"
    is DirectoryNotEmptyException -> "the folder isn't empty"
    else -> e.javaClass.simpleName
}

private fun namesAPath(text: String): Boolean = '\\' in text || '/' in text
