package com.seenot.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AccountActionsCopyTest {
    @Test
    fun plusAccountActionsHaveDedicatedMenuCopyInSupportedLocales() {
        assertEquals(
            "开通 Plus",
            stringResourceValue("src/main/res/values/strings.xml", "subscribe_plus_action")
        )
        assertEquals(
            "续费 Plus",
            stringResourceValue("src/main/res/values/strings.xml", "renew_plus_action")
        )
        assertEquals(
            "Upgrade to Plus",
            stringResourceValue("src/main/res/values-en/strings.xml", "subscribe_plus_action")
        )
        assertEquals(
            "Renew Plus",
            stringResourceValue("src/main/res/values-en/strings.xml", "renew_plus_action")
        )
    }

    private fun stringResourceValue(path: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(path))
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val node = strings.item(index)
            if (node.attributes.getNamedItem("name")?.nodeValue == name) {
                return node.textContent
            }
        }
        error("String resource $name not found in $path")
    }
}
