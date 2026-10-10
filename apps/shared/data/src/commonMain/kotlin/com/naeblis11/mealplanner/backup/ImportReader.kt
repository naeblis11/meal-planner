package com.naeblis11.mealplanner.backup

import com.naeblis11.mealplanner.domain.MealMaster
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A recipe read from an import file, as its YAML map. */
data class IncomingRecipe(val title: String, val doc: YamlMap)

/**
 * Everything one import file held: recipes, photos by file name (files in the
 * staging directory given to [ImportReader.read]), and (item, reason) for what
 * couldn't be read.
 */
data class ImportBundle(
    val sourceName: String,
    val recipes: List<IncomingRecipe>,
    val images: Map<String, File>,
    val errors: List<Pair<String, String>>,
)

/** The file as a whole can't be imported (wrong type, unreadable or oversized). */
class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads an import file: ORF `.yaml`/`.yml`, Meal Master `.mmf`, or a `.zip`
 * (a phone or Pi backup: YAML files anywhere, photos in any
 * `recipe-images/` folder). Nothing is written to the library; this only reads.
 */
object ImportReader {
    const val MAX_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024
    const val IMAGES_DIR = "recipe-images"
    private val IMAGE_NAME = Regex("[A-Za-z0-9._-]+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    /**
     * Reads [input] as the file [fileName], on [dispatcher]; [input] is not closed.
     * A zip's photos are streamed into [stagingDir] (created if needed) instead of
     * being held in memory, and the bundle's images are those files. The caller
     * owns [stagingDir]: use a fresh one per import and delete it once the import
     * is confirmed or cancelled. Everything read -- the file itself, or a zip's
     * unpacked YAML and photos -- counts toward [maxUncompressedBytes].
     */
    suspend fun read(
        fileName: String,
        input: InputStream,
        stagingDir: File,
        maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ImportBundle = withContext(dispatcher) {
        val lower = fileName.lowercase(Locale.ROOT)
        when {
            lower.endsWith(".yaml") || lower.endsWith(".yml") -> {
                val bytes = readWholeFile(fileName, input, maxUncompressedBytes)
                val recipes = mutableListOf<IncomingRecipe>()
                val errors = mutableListOf<Pair<String, String>>()
                readYaml(fileName, bytes, recipes, errors)
                ImportBundle(fileName, recipes, emptyMap(), errors)
            }
            lower.endsWith(".mmf") -> {
                val bytes = readWholeFile(fileName, input, maxUncompressedBytes)
                val result = MealMaster.parse(MealMaster.decode(bytes))
                ImportBundle(fileName, result.recipes.map { IncomingRecipe(it.title, it.data) }, emptyMap(), result.errors)
            }
            lower.endsWith(".zip") -> readZip(fileName, input, stagingDir, maxUncompressedBytes)
            else -> throw ImportException("Unsupported file type: $fileName")
        }
    }

    private fun tooLarge(fileName: String, maxBytes: Long) =
        ImportException("$fileName unpacks to more than ${maxBytes / (1024 * 1024)} MB, too large to import.")

    private fun readWholeFile(fileName: String, input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var total = 0L
        try {
            copyCounted(input, out) { read ->
                total += read
                if (total > maxBytes) throw tooLarge(fileName, maxBytes)
            }
        } catch (e: IOException) {
            throw ImportException("Could not read $fileName.", e)
        }
        return out.toByteArray()
    }

    private fun readYaml(
        label: String,
        bytes: ByteArray,
        recipes: MutableList<IncomingRecipe>,
        errors: MutableList<Pair<String, String>>,
    ) {
        try {
            val text = decodeUtf8(bytes).removePrefix("\uFEFF")
            val parsed = Orf.parse(text)
            @Suppress("UNCHECKED_CAST")
            recipes += IncomingRecipe(Py.str(parsed["name"]), RecipeYaml.load(text) as YamlMap)
        } catch (e: RecipeFormatException) {
            errors += label to (e.message ?: "could not be read")
        } catch (e: CharacterCodingException) {
            errors += label to "is not UTF-8 text"
        } catch (e: RuntimeException) {
            // Catch unexpected errors (e.g., YAML parsing or Orf failures) to keep per-file isolation.
            // One bad file shouldn't abort the entire import.
            errors += label to "could not be read: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // Strict, like Python's bytes.decode("utf-8").
    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun readZip(fileName: String, input: InputStream, stagingDir: File, maxUncompressedBytes: Long): ImportBundle {
        val recipes = mutableListOf<IncomingRecipe>()
        val images = linkedMapOf<String, File>()
        val errors = mutableListOf<Pair<String, String>>()
        var total = 0L
        val count: (Int) -> Unit = { read ->
            total += read
            if (total > maxUncompressedBytes) throw tooLarge(fileName, maxUncompressedBytes)
        }
        try {
            // Closing the zip stream must not close the caller's input.
            ZipInputStream(NonClosingInputStream(input)).use { zip ->
                var firstEntry = true
                while (true) {
                    val entry = zip.nextEntry
                    if (entry == null) {
                        if (firstEntry) throw ImportException("Could not read $fileName as a zip file.")
                        break
                    }
                    firstEntry = false
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory || name.endsWith("/") || name.startsWith("__MACOSX/")) continue
                    if (isUnsafe(name)) {
                        errors += name to "skipped: unsafe path in the zip"
                        continue
                    }
                    val segments = name.split('/')
                    val base = segments.last()
                    val lowerBase = base.lowercase(Locale.ROOT)
                    val isYaml = lowerBase.endsWith(".yaml") || lowerBase.endsWith(".yml")
                    val isImage = segments.dropLast(1).lastOrNull() == IMAGES_DIR && IMAGE_NAME.matches(base)
                    if (isYaml) {
                        val data = ByteArrayOutputStream().also { copyCounted(zip, it, count) }.toByteArray()
                        readYaml(name, data, recipes, errors)
                    } else if (isImage) {
                        // [base] matched IMAGE_NAME, so it is a bare file name, safe inside the staging folder.
                        stagingDir.mkdirs()
                        val file = File(stagingDir, base)
                        file.outputStream().use { copyCounted(zip, it, count) }
                        images[base] = file
                    }
                }
            }
        } catch (e: IOException) {
            throw ImportException("Could not read $fileName as a zip file.", e)
        } catch (e: IllegalArgumentException) {
            throw ImportException("Could not read $fileName as a zip file.", e)
        }
        return ImportBundle(fileName, recipes, images, errors)
    }

    private fun isUnsafe(name: String): Boolean =
        name.startsWith("/") || DRIVE_LETTER.containsMatchIn(name) || name.split('/').any { it == ".." }

    private fun copyCounted(input: InputStream, out: OutputStream, onRead: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            onRead(read)
            out.write(buffer, 0, read)
        }
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }
}
