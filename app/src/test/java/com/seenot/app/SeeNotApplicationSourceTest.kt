package com.seenot.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeeNotApplicationSourceTest {
    @Test
    fun installsRxJavaUndeliverableErrorHandlerBeforeDashScopeSdkUse() {
        val source = File("src/main/java/com/seenot/app/SeeNotApplication.kt").readText()
        val onCreateBody = source.substringAfter("override fun onCreate()")
            .substringBefore("/**\n     * Setup global uncaught exception handler")

        assertTrue(source.contains("RxJavaPlugins.setErrorHandler"))
        assertTrue(source.contains("UndeliverableException"))
        assertTrue(onCreateBody.indexOf("setupRxJavaErrorHandler()") < onCreateBody.indexOf("ApiConfig.init(this)"))
    }
}
