package com.attentionguard.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiProviderTest {

    @Test
    fun storedValuesAlwaysResolveToDeepSeekForProductRequests() {
        assertEquals(ApiProvider.DEEPSEEK_OFFICIAL, ApiProvider.fromStorage(ApiProvider.DEEPSEEK_OFFICIAL.storageValue))
        assertEquals(ApiProvider.DEEPSEEK_OFFICIAL, ApiProvider.fromStorage("openrouter"))
    }

    @Test
    fun missingOrUnknownValueUsesDeepSeekDefault() {
        assertEquals(ApiProvider.DEEPSEEK_OFFICIAL, ApiProvider.fromStorage(null))
        assertEquals(ApiProvider.DEEPSEEK_OFFICIAL, ApiProvider.fromStorage("unknown"))
    }
}
