package com.leap8.offline

import android.app.Application
import com.leap8.offline.ai.OfflineAiModelManager
import com.leap8.offline.ai.OfflineAiService
import com.leap8.offline.ai.AiBackendPreferences
import com.leap8.offline.ai.AiServiceRouter
import com.leap8.offline.data.LearningRepository
import com.leap8.offline.domain.AiRevisionCoordinator
import com.leap8.offline.domain.EssayScoringCoordinator

class Leap8Application : Application() {
    val developerTelemetry: com.leap8.offline.diagnostics.DeveloperTelemetry by lazy {
        com.leap8.offline.diagnostics.DeveloperTelemetry(this)
    }
    val repository: LearningRepository by lazy { LearningRepository(this) }
    val aiModelManager: OfflineAiModelManager by lazy { OfflineAiModelManager(this) }
    val aiBackendPreferences: AiBackendPreferences by lazy { AiBackendPreferences(this) }
    val offlineAiService: OfflineAiService by lazy { OfflineAiService(this, aiModelManager) }
    val aiServiceRouter: AiServiceRouter by lazy {
        AiServiceRouter(aiBackendPreferences, offlineAiService, telemetry = developerTelemetry)
    }
    val essayScoringCoordinator: EssayScoringCoordinator by lazy {
        EssayScoringCoordinator(aiServiceRouter)
    }
    val aiRevisionCoordinator: AiRevisionCoordinator by lazy {
        AiRevisionCoordinator(aiServiceRouter)
    }
}
