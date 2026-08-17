package io.github.jcartu.hermex.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `decodes snake_case model provider profile and reasoning responses`() {
        val models = json.decodeFromString(
            ModelsResponse.serializer(),
            """
            {
              "groups": [
                {
                  "provider_id": "anthropic",
                  "name": "Anthropic",
                  "models": [
                    {"id": "claude-sonnet-4", "name": "Claude Sonnet 4"},
                    {"id": "claude-opus-4", "label": "Claude Opus 4"}
                  ],
                  "extra_models": [
                    {"id": "claude-3-5-haiku", "label": "Claude 3.5 Haiku"}
                  ]
                }
              ],
              "models": [
                {"id": "claude-sonnet-4", "provider_id": "anthropic"}
              ],
              "default_model": "claude-sonnet-4",
              "active_provider": "anthropic"
            }
            """.trimIndent(),
        )
        val providers = json.decodeFromString(
            ProvidersResponse.serializer(),
            """
            {
              "providers": [
                {
                  "id": "anthropic",
                  "display_name": "Anthropic",
                  "has_key": "yes",
                  "configurable": true,
                  "is_self_hosted": false,
                  "base_url": "https://api.anthropic.com",
                  "is_plugin_provider": false,
                  "is_oauth": false,
                  "is_custom": false,
                  "key_source": "env_var",
                  "auth_error": null,
                  "models": [
                    {"id": "claude-sonnet-4", "label": "Claude Sonnet 4"},
                    "claude-3-5-haiku"
                  ],
                  "models_total": "27"
                }
              ],
              "active_provider": "anthropic"
            }
            """.trimIndent(),
        )
        val profiles = json.decodeFromString(
            ProfilesResponse.serializer(),
            """
            {
              "profiles": [
                {
                  "name": "default",
                  "path": "/srv/hermes/default",
                  "is_default": true,
                  "is_active": false,
                  "gateway_running": true,
                  "model": "claude-sonnet-4",
                  "provider": "anthropic",
                  "has_env": true,
                  "skill_count": 12
                },
                {
                  "name": "work",
                  "path": "/srv/hermes/work",
                  "is_default": false,
                  "is_active": true,
                  "gateway_running": false,
                  "model": "claude-opus-4",
                  "provider": "anthropic",
                  "has_env": false,
                  "skill_count": 3
                }
              ],
              "active": " work ",
              "single_profile_mode": false
            }
            """.trimIndent(),
        )
        val reasoning = json.decodeFromString(
            ReasoningStatusResponse.serializer(),
            """
            {
              "ok": true,
              "show_reasoning": true,
              "reasoning_effort": "High",
              "effort": "medium",
              "supported_efforts": [" High ", "low", "HIGH", "", " medium "],
              "supports_reasoning_effort": true,
              "error": null
            }
            """.trimIndent(),
        )

        assertEquals("claude-sonnet-4", models.defaultModel)
        assertEquals("anthropic", models.activeProvider)
        assertEquals(1, models.catalogGroups.size)
        assertEquals("Claude Opus 4", models.displayName("claude-opus-4"))
        assertEquals(3, models.catalogGroups.single().slashAutocompleteModels.size)

        assertEquals("anthropic", providers.activeProvider)
        val provider = providers.providers!!.single()
        assertEquals("Anthropic", provider.displayName)
        assertEquals(true, provider.hasKey)
        assertEquals(27, provider.modelsTotal)
        assertEquals("claude-sonnet-4", provider.models!![0].id)
        assertEquals("claude-3-5-haiku", provider.models!![1].id)
        assertEquals("claude-3-5-haiku", provider.models!![1].label)

        assertFalse(profiles.singleProfileMode!!)
        assertEquals("work", profiles.effectiveDefaultProfileName)
        assertEquals("Default", profiles.displayName(" default "))
        assertEquals(12, profiles.profile("default")!!.skillCount)

        assertTrue(reasoning.showReasoning!!)
        assertEquals("High", reasoning.effectiveEffort)
        assertEquals(listOf("high", "low", "medium"), reasoning.normalizedSupportedEfforts)
        assertEquals(true, reasoning.supportsReasoningEffort)
    }

    @Test
    fun `unknown extra fields are ignored at every profile level`() {
        val response = json.decodeFromString(
            ProfilesResponse.serializer(),
            """
            {
              "profiles": [
                {
                  "name": "research",
                  "path": "/srv/hermes/research",
                  "is_default": false,
                  "is_active": true,
                  "gateway_running": true,
                  "model": "claude-sonnet-4",
                  "provider": "anthropic",
                  "has_env": true,
                  "skill_count": 8,
                  "future_profile_state": {"color": "gold", "rank": 1}
                }
              ],
              "active": "research",
              "single_profile_mode": true,
              "future_catalog_metadata": [1, 2, 3]
            }
            """.trimIndent(),
        )

        assertEquals("research", response.active)
        assertTrue(response.singleProfileMode!!)
        assertEquals("research", response.profiles!!.single().name)
        assertEquals(8, response.profiles!!.single().skillCount)
    }

    @Test
    fun `JSONValue group and option parsing skips mixed malformed entries`() {
        val response = json.decodeFromString(
            ModelsResponse.serializer(),
            """
            {
              "groups": [
                {
                  "provider_id": " anthropic ",
                  "name": " Anthropic ",
                  "models": [
                    {"id": " model-a ", "name": " Model A "},
                    99,
                    null,
                    {"id": "   ", "label": "Blank"},
                    {"id": 12, "label": "Numeric IDs are not strings"},
                    {"id": "model-b", "label": " Model B ", "provider_id": " gateway "}
                  ],
                  "extra_models": [
                    {"id": "model-b", "label": "Duplicate B"},
                    {"id": "extra", "label": " Extra "},
                    {"name": "missing id"},
                    "bad-extra"
                  ]
                },
                "not-an-object",
                {
                  "name": "No valid models",
                  "models": [{"label": "missing id"}, false]
                },
                {
                  "models": [{"id": "model-c"}]
                }
              ]
            }
            """.trimIndent(),
        )

        val groups = response.catalogGroups
        assertEquals(2, groups.size)

        val anthropic = groups[0]
        assertEquals("anthropic", anthropic.id)
        assertEquals("Anthropic", anthropic.name)
        assertEquals("anthropic", anthropic.providerID)
        assertEquals(listOf("model-a", "model-b"), anthropic.models.map { it.id })
        assertEquals("Model A", anthropic.models[0].displayName)
        assertEquals("anthropic", anthropic.models[0].providerID)
        assertEquals("gateway", anthropic.models[1].providerID)
        assertEquals(listOf("model-b", "extra"), anthropic.extraModels.map { it.id })
        assertEquals(
            listOf("model-a", "model-b", "extra"),
            anthropic.slashAutocompleteModels.map { it.id },
        )

        val unnamed = groups[1]
        assertEquals("Models-3", unnamed.id)
        assertEquals("Models", unnamed.name)
        assertNull(unnamed.providerID)
        assertEquals("model-c", unnamed.models.single().displayName)

        val live = json.decodeFromString(
            ModelsLiveResponse.serializer(),
            """
            {
              "provider": " anthropic ",
              "models": [
                {"id": " live-a ", "label": " Live A "},
                "bad-live-entry",
                {"id": ""},
                {"id": "live-b", "provider_id": " gateway "}
              ],
              "count": 4
            }
            """.trimIndent(),
        )

        assertEquals("anthropic", live.normalizedProvider)
        assertEquals(listOf("live-a", "live-b"), live.liveOptions.map { it.id })
        assertEquals("anthropic", live.liveOptions[0].providerID)
        assertEquals("gateway", live.liveOptions[1].providerID)
    }
}
