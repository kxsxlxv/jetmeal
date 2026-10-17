package com.kxsxlxv.jetmeal.voice

/** Experimental requested modes. Never automatically substitute Basic for
 * Advanced: that would make the quality comparison misleading. */
internal enum class MlKitMode(val label: String) {
    Basic("Basic"),
    Advanced("Advanced");

    val selectionHint: String get() = when (this) {
        Basic -> "Basic: быстрый локальный распознаватель, уже проверенный на vivo X300."
        Advanced -> "Advanced: генеративная модель Google. Доступность зависит от AICore и разрешённых Google устройств; на vivo X300 не гарантируется."
    }

    fun unavailableMessage(): String = when(this) {
        Basic -> "ML Kit Basic ru-RU недоступен на телефоне."
        Advanced -> "ML Kit Advanced ru-RU недоступен на этом устройстве. Google пока ограничивает поддержку отдельными Pixel. Basic остаётся доступен независимо от этого теста."
    }

    fun downloadableMessage(): String = when(this) {
        Basic -> "Для ML Kit Basic ru-RU нужно загрузить системную модель Google."
        Advanced -> "Сервис разрешил подготовку Advanced ru-RU. Для теста требуется скачать модель через Google."
    }
}

/** AICore sometimes throws feature 606 instead of returning UNAVAILABLE when
 * this device has not been provisioned for the requested model. */
internal fun mlKitFeatureNotProvisioned(message: String?): Boolean {
    val text=message.orEmpty().uppercase(java.util.Locale.ROOT)
    return "FEATURE_NOT_FOUND" in text || Regex("""(?:^|\D)606(?:\D|$)""").containsMatchIn(text)
}
