package com.seenot.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionImprovementSuggestionRepositorySourceTest {
    @Test
    fun repositoryKeepsAcceptedAndDismissedSuggestionsOutOfNextEntry() {
        val repositorySource = File("src/main/java/com/seenot/app/data/repository/SessionImprovementSuggestionRepository.kt").readText()
        val daoSource = File("src/main/java/com/seenot/app/data/local/dao/SessionImprovementSuggestionDao.kt").readText()

        assertTrue(repositorySource.contains("getPendingSuggestionForPackage"))
        assertTrue(daoSource.contains("acceptedAt IS NULL"))
        assertTrue(daoSource.contains("dismissedAt IS NULL"))
        assertTrue(repositorySource.contains("acceptNextIntent"))
        assertTrue(repositorySource.contains("dismiss"))
    }
}
