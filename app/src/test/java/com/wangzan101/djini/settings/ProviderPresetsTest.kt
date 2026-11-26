package com.wangzan101.djini.settings

import com.wangzan101.djini.i18n.ChineseTexts
import com.wangzan101.djini.ui.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** 地址、名字不重复(按地址认预设);云端的都走 https,明文 http 只给自己机器上跑的。 */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
    }

    /** 自己跑的服务:地址可改、Key 可不填,名字仍用预设名;云端预设地址不给改、Key 必填。 */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("不填 Key 也能测试", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** 主机名对得上就认出是哪家(路径不论);自己跑的服务、认不出的地址不算。 */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertEquals("Command Code", ProviderPresets.forHost("https://api.commandcode.ai/provider/v1")?.label)
        assertEquals("Zhipu (GLM)", ProviderPresets.forHost("https://open.bigmodel.cn/api/paas/v4")?.label)
        assertEquals("Zhipu Coding Plan", ProviderPresets.forHost("https://open.bigmodel.cn/api/coding/paas/v4")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // 每个有地址、不是自己跑的预设都认得出自己
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }

    /** DeepSeek 的 V4 两个模型上下文都是 1M:窗口按旧默认 64k 时压缩频繁改写前缀,前缀缓存整段失效。 */
    @Test fun deepSeekPresetUsesTheFullWindow() {
        val deepseek = preset("DeepSeek")
        assertEquals(1_000_000, deepseek.capabilities.contextTokens)
        assertFalse(deepseek.capabilities.vision)
    }

    /** 预置的思考档位只来自官方文档:DeepSeek 的 reasoning_effort 可能值是 none/low/high/max(none 关思考),别名不预置。 */
    @Test fun reasoningLevelsAreCuratedAndOfficialOnly() {
        assertEquals(listOf("none", "low", "high", "max"), preset("DeepSeek").reasoningLevels)
        // 没核实的店家一律不预置,界面上照旧不出档位(见 ReasoningOptions)
        ProviderPresets.all.filter { it.label != "DeepSeek" }.forEach { p ->
            assertEquals(p.label, emptyList<String>(), p.reasoningLevels)
        }
    }

    /** Coding Plan 的 Key 和标准 API 不通用;地址要落在官方分开的 OpenAI 兼容端点上。 */
    @Test fun codingPlanUsesItsOwnDocumentedEndpoint() {
        val standard = preset("Zhipu (GLM)")
        val coding = preset("Zhipu Coding Plan")
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", coding.baseUrl)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, coding.kind)
        assertNotEquals(standard.baseUrl, coding.baseUrl)
    }

    @Test fun commandCodeRoutesClaudeModelsToMessagesEndpoint() {
        val cc = preset("Command Code")
        val claudeCfg = ProviderConfig(
            id = "c1", providerId = "p1", name = cc.label, kind = cc.kind,
            baseUrl = cc.baseUrl, model = "claude-sonnet-5-5", capabilities = cc.capabilities,
        )
        val claudeListed = com.wangzan101.djini.core.model.ListedModel(
            id = "claude-sonnet-5-5",
            protocols = listOf(com.wangzan101.djini.core.model.ModelProtocol.Messages),
        )
        val claudeEndpoint = ProviderStore.effectiveEndpoint(claudeCfg, claudeListed)
        val (claudeKind, claudeBase) = ProviderStore.adapterFor(claudeCfg, claudeEndpoint)
        assertEquals(ProviderConfig.Kind.Anthropic, claudeKind)
        assertEquals("https://api.commandcode.ai/provider", claudeBase)

        val gptCfg = claudeCfg.copy(model = "gpt-6-astra")
        val gptListed = com.wangzan101.djini.core.model.ListedModel(
            id = "gpt-6-astra",
            protocols = listOf(
                com.wangzan101.djini.core.model.ModelProtocol.ChatCompletions,
                com.wangzan101.djini.core.model.ModelProtocol.Responses,
            ),
        )
        assertNull(ProviderStore.effectiveEndpoint(gptCfg, gptListed))
    }
}
