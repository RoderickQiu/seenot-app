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

    @Test
    fun aiChoiceCopyGuidesNovicesToPlusAndOwnKeySetup() {
        assertEquals(
            "开通 Plus",
            stringResourceValue("src/main/res/values/strings.xml", "open_plus_no_config_action")
        )
        assertEquals(
            "自带 API Key",
            stringResourceValue("src/main/res/values/strings.xml", "use_own_api_key_action")
        )
        assertEquals(
            "AI 设置",
            stringResourceValue("src/main/res/values/strings.xml", "ai_model_settings_title")
        )
        assertEquals(
            "Get Plus",
            stringResourceValue("src/main/res/values-en/strings.xml", "open_plus_no_config_action")
        )
        assertEquals(
            "My API key",
            stringResourceValue("src/main/res/values-en/strings.xml", "use_own_api_key_action")
        )
        assertEquals(
            "AI Settings",
            stringResourceValue("src/main/res/values-en/strings.xml", "ai_model_settings_title")
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
