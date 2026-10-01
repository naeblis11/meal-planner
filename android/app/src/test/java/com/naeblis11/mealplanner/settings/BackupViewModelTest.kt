package com.naeblis11.mealplanner.settings

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var vm: BackupViewModel
    private val created = mutableListOf<BackupViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val images = Files.createTempDirectory("images").toFile()
        val repo = RecipeRepository(db, images)
        @Suppress("UNCHECKED_CAST")
        kotlinx.coroutines.runBlocking { repo.save(RecipeYaml.load("recipe_name: Soup\ningredients: []\nsteps: []\n") as YamlMap) }
        vm = BackupViewModel(repo, images).also { created += it }
    }

    // Each ViewModel's scope is cancelled before the database closes and before the Main
    // rule resets Main, so nothing still running can touch either (see RecipeDetailViewModelTest).
    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Test
    fun exportsTheLibrary() = runTest {
        val out = ByteArrayOutputStream()
        vm.export(open = { out }, deleteDestination = { error("must not delete") })
        assertEquals(BackupState.Done("Saved 1 recipe(s) and 0 photo(s)."), vm.state.first { it is BackupState.Done })
        ZipInputStream(out.toByteArray().inputStream()).use { assertEquals("recipes/soup.yaml", it.nextEntry!!.name) }
    }

    @Test
    fun aFailedExportDeletesTheHalfWrittenFile() = runTest {
        var deleted = false
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }
        vm.export(open = { broken }, deleteDestination = { deleted = true })
        val failed = vm.state.first { it is BackupState.Failed } as BackupState.Failed
        assertTrue(failed.message.startsWith("The backup could not be saved:"))
        assertTrue(deleted)
    }

    @Test
    fun aSecondExportWhileWorkingIsIgnored() = runTest {
        val latch = CountDownLatch(1)
        val opened = AtomicInteger()
        val open: () -> OutputStream = {
            opened.incrementAndGet()
            latch.await()
            ByteArrayOutputStream()
        }
        try {
            vm.export(open = open, deleteDestination = {})
            assertEquals(BackupState.Working, vm.state.value)
            vm.export(open = open, deleteDestination = {})
        } finally {
            latch.countDown()
        }
        vm.state.first { it is BackupState.Done }
        assertEquals(1, opened.get())
    }
}
