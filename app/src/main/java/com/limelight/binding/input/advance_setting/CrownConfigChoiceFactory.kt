package com.limelight.binding.input.advance_setting

import com.limelight.binding.input.advance_setting.share.CrownProfileSchema
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper

/** Builds the data consumed by the shared Crown profile picker. */
object CrownConfigChoiceFactory {
    @JvmStatic
    fun create(
        database: SuperConfigDatabaseHelper,
        orderedConfigIds: List<Long>,
        currentConfigId: Long
    ): List<CrownConfigPickerDialog.ConfigChoice> = orderedConfigIds.map { configId ->
        val name = database.queryConfigAttribute(
            configId,
            CrownProfileSchema.CONFIG_NAME_KEY,
            "default"
        ) as? String ?: "default"
        val preview = runCatching {
            CrownProfileShareManager.parsePreview(database.exportConfig(configId))
        }.getOrNull()

        CrownConfigPickerDialog.ConfigChoice(
            name = name,
            elementCount = database.queryAllElementIds(configId).size,
            preview = preview,
            configId = configId,
            isCurrent = configId == currentConfigId,
            isSelectable = configId != currentConfigId
        )
    }
}
