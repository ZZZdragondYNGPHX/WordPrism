package dev.wordprism.engine

import dev.wordprism.contract.*

internal fun validateTrainingPolicy(db: Database, value: TrainingPolicy, budgetLimit: Int = 240) {
    requireBusiness(value.version > 0 && value.newTargetLimit in 0..100 && value.reviewTargetLimit in 0..500 && value.sessionTargetLimit in 1..50, "学习数量越界")
    requireBusiness(value.plannedMinutes in 1..budgetLimit && value.retention.isFinite() && value.retention in 0.7f..0.97f && value.maxIntervalDays in 1..36500, "日预算或调度参数越界")
    requireBusiness(value.mix.keys.none { it in introductionKinds } && value.mix.values.all { it in 0..100 } && value.mix.values.sum() > 0, "题型比例无效")
    requireBusiness((value.focusTargets + value.suspendedTargets).all { it in db.targets }, "目标不存在")
    requireBusiness(value.hintPolicy in setOf("on_request", "after_attempt") && value.passageWords in 30..300 && value.reuseAfterDays in 0..365 &&
        value.backgroundDifficulty in setOf("simple", "intermediate", "advanced") && value.preGenerateLimit in 0..10, "训练策略无效")
}

internal fun validateCallSettings(db: Database, value: UserSettings) {
    requireBusiness(value.maxRetries in 0..3 && (value.dailyRequestLimit == null || value.dailyRequestLimit in 0..1000) && value.preGenerationMaxPerDay in 0..10, "调用控制范围无效")
    requireBusiness(listOfNotNull(value.defaultPresetId, value.fallbackPresetId).all { it in db.presets }, "默认或备用预设不存在", ErrorCode.PRESET_MISSING)
}
