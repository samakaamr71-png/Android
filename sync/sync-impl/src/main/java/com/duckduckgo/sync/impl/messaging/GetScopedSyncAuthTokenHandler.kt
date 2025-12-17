/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.sync.impl.messaging

import com.duckduckgo.common.utils.AppUrl
import com.duckduckgo.contentscopescripts.api.ContentScopeJsMessageHandlersPlugin
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.js.messaging.api.JsCallbackData
import com.duckduckgo.js.messaging.api.JsMessage
import com.duckduckgo.js.messaging.api.JsMessageCallback
import com.duckduckgo.js.messaging.api.JsMessageHandler
import com.duckduckgo.js.messaging.api.JsMessaging
import com.duckduckgo.sync.impl.Result
import com.duckduckgo.sync.impl.SyncApi
import com.duckduckgo.sync.store.SyncStore
import com.squareup.anvil.annotations.ContributesMultibinding
import logcat.LogPriority
import logcat.logcat
import org.json.JSONObject
import javax.inject.Inject

@ContributesMultibinding(AppScope::class)
class GetScopedSyncAuthTokenHandler @Inject constructor(
    private val syncApi: SyncApi,
    private val syncStore: SyncStore,
) : ContentScopeJsMessageHandlersPlugin {
    override fun getJsMessageHandler(): JsMessageHandler =
        object : JsMessageHandler {
            override fun process(
                jsMessage: JsMessage,
                jsMessaging: JsMessaging,
                jsMessageCallback: JsMessageCallback?,
            ) {
                if (jsMessage.id.isNullOrEmpty()) return

                logcat(LogPriority.WARN) { "cdr ${jsMessage.method} called" }

                logcat(LogPriority.ERROR) { "cdr sync store ($syncStore) token is ${syncStore.token}" }
                val token = syncStore.token.takeUnless { it.isNullOrEmpty() }
                    ?: run {
                        val errorPayload = JSONObject().apply {
                            put("ok", false)
                            put("error", "No sync token available")
                        }
                        jsMessaging.onResponse(JsCallbackData(errorPayload, featureName, jsMessage.method, jsMessage.id!!))
                        return
                    }

                val jsonPayload = when (val result = syncApi.rescopeToken(token, "ai_chats")) {
                    is Result.Success -> {
                        logcat(LogPriority.INFO) { "cdr rescope token succeeded" }
                        JSONObject().apply {
                            put("ok", true)
                            put(
                                "payload",
                                JSONObject().apply {
                                    put("token", result.data)
                                },
                            )
                        }
                    }
                    is Result.Error -> {
                        logcat(LogPriority.ERROR) { "cdr rescope token failed: code=${result.code}, reason=${result.reason}" }
                        JSONObject().apply {
                            put("ok", false)
                            put("error", result.reason)
                            put("code", result.code)
                        }
                    }
                }

                jsMessaging.onResponse(JsCallbackData(jsonPayload, featureName, jsMessage.method, jsMessage.id!!)).also {
                    logcat { "cdr responded to ${jsMessage.method} with $jsonPayload" }
                }
            }

            override val allowedDomains: List<String> =
                listOf(
                    AppUrl.Url.HOST,
                    "duck.ai",
                )

            override val featureName: String = "aiChat"
            override val methods: List<String> = listOf("getScopedSyncAuthToken")
        }
}
