package com.naeblis11.mealplanner.shopping

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainActivity
import com.naeblis11.mealplanner.app.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class CheckOffSurvivesRecreationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shoppingTab = hasText("Shopping") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun aTickIsInTheDatabaseAtOnceAndSurvivesRecreatingTheActivity() {
        val container = ApplicationProvider.getApplicationContext<Context>().appContainer()
        val id = runBlocking { container.shopping.addItem("Milk").id }
        compose.onNode(shoppingTab).performClick()
        waitForMilk()
        compose.onNode(hasText("Milk") and isToggleable()).performClick()

        // In the database straight away: a process killed now still has the tick.
        compose.waitUntil(5_000) { runBlocking { container.database.shoppingDao().item(id)!!.checked } }

        compose.activityRule.scenario.recreate()

        compose.onNode(shoppingTab).assertIsSelected()
        waitForMilk()
        compose.onNode(hasText("Milk") and isToggleable()).assertIsOn()
    }

    // The list loads on an IO thread; waitForIdle does not wait for it.
    private fun waitForMilk() = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodes(hasText("Milk") and isToggleable()).fetchSemanticsNodes().isNotEmpty()
    }
}
