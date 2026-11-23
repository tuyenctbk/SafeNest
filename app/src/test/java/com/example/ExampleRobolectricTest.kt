package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.OnDeviceSafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SafeNest", appName)
  }

  @Test
  fun `on-device safety engine avoids substring false positives`() {
    val safe1 = OnDeviceSafetyEngine.analyzeText("The baby fell asleep near the skyscrapers", deduplicate = false)
    assertFalse(safe1.flagged)

    val risk = OnDeviceSafetyEngine.analyzeText("Don't tell your parents, keep this secret and send me a pic", deduplicate = false)
    assertTrue(risk.flagged)
  }
}
