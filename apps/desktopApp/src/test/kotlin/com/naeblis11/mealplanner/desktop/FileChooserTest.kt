package com.naeblis11.mealplanner.desktop

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.FileChooser
import com.naeblis11.mealplanner.ui.LocalFileChooser
import com.naeblis11.mealplanner.ui.rememberLaunchGuard
import com.naeblis11.mealplanner.ui.rememberOpenFile
import java.awt.FileDialog
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** P3-R5: the file dialogs come from LocalFileChooser, which Main fills with one owned by the main window. */
class FileChooserTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theImportDialogComesFromTheWindowsChooser() {
        val file = Files.createTempFile("mp-pick", ".yaml").toFile()
        try {
            val asked = mutableListOf<Pair<Int, String>>()
            var picked: String? = null
            val chooser = FileChooser { mode, title, _ ->
                asked += mode to title
                file
            }
            compose.showAt(600.dp) {
                CompositionLocalProvider(LocalFileChooser provides chooser) {
                    val open = rememberOpenFile(rememberLaunchGuard()) { picked = it.name() }
                    TextButton(onClick = open) { Text("Import") }
                }
            }
            compose.onNodeWithText("Import").click()
            compose.waitForIdle()
            assertEquals(listOf(FileDialog.LOAD to "Import recipes"), asked)
            assertEquals(file.name, picked)
        } finally {
            file.delete()
        }
    }
}
