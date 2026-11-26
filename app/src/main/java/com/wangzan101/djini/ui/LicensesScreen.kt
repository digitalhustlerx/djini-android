package com.wangzan101.djini.ui

import com.wangzan101.djini.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.djini.ui.components.FooterNote
import com.wangzan101.djini.ui.components.GroupCard
import com.wangzan101.djini.ui.components.GroupLabel
import com.wangzan101.djini.ui.components.ListRow
import com.wangzan101.djini.ui.components.DjiniCard
import com.wangzan101.djini.ui.components.SubPage
import com.wangzan101.djini.ui.theme.DjiniTheme
import com.wangzan101.djini.ui.theme.DjiniType

/** 许可证页上第三方组件的数据;与仓库 licenses/THIRD_PARTY_NOTICES.md 的条目一一对应(LicensesTest 核对)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot (GPL-2.0-or-later) and talloc (LGPL-3.0-or-later) are bundled as standalone binaries with the Djini APK without linking. " +
            "Their complete source code, Djini patches, and build scripts are published alongside each release as djini-<version>-gpl-sources.tar.gz; " +
            "source URLs, checksums, patches, and build scripts are also available in tools/sandbox/."

    const val RUNTIME_NOTE =
        "The Alpine Linux 3.22 runtime used to run Python, Node, and other tools is bundled with the Djini APK; individual packages and licenses are listed below. " +
            "Source code for GPL, LGPL, and MPL packages (Alpine build recipes and upstream sources) is also included in djini-<version>-gpl-sources.tar.gz."

    /** 自带运行环境里的软件包(assets/runtime/packages.json,由 tools/sandbox/build-runtime.py 生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "Version ${f("version")}. ${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Djini", "Apache-2.0", "Djini itself, including the runtime launcher, built-in djini CLI, and skill scripts. Full license text is shown at the top of this page."),
        Entry("PRoot", "GPL-2.0-or-later", "Sandboxed runtime environment for Python, Node, and tools, version 5.4.0, with a patch to terminate child processes on PRoot exit. $GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "Version 2.4.3, unmodified, statically linked into the standalone PRoot binary. Relinkable using the sources and scripts in tools/sandbox/."),
        Entry("Apache Commons Compress", "Apache-2.0", "Unpacks the bundled runtime environment on first launch. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "Dependency of Apache Commons Compress. Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin Standard Library", "Apache-2.0", "kotlin-stdlib 2.1.20 and JetBrains annotations 23.0.0. https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "Version 1.9.0. https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "Version 1.7.3. https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "OTLP message schemas for detailed traces. https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "Protobuf encoding for detailed traces. https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "Version 4.12.0, used for model API requests. https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "Version 3.6.0, dependency of OkHttp. https://github.com/square/okio/"),
        Entry("AndroidX (including Jetpack Compose)", "Apache-2.0", "Compose 1.7.6, Material3 1.3.1, Activity 1.9.3, Lifecycle 2.8.3, Core 1.13.1, WebKit 1.12.1, and related UI/system libraries. https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture (via AndroidX)", "Apache-2.0", "listenablefuture 1.0. https://github.com/google/guava"),
        Entry("scrcpy", "Apache-2.0", "Approach for obtaining a system Context as the shell user is modeled after scrcpy; attribution is preserved in the source headers."),
        Entry("Phone Calls (calls/) Implementation", "Apache-2.0", "Phone call handling (dialing, answering, hang-up, AI call handling, human takeover, PCM audio bridge, and real-time voice client) under Apache-2.0; real-time voice uses Volcengine WebSocket APIs."),
        Entry("Alpine Linux", "Per-package licenses", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "Version 1.10, decompresses the bundled runtime on first launch. https://tukaani.org/xz/java.html"),
        Entry("ZXing", "Apache-2.0", "Version 3.5.3, generates WeChat login QR codes locally on device. https://github.com/zxing/zxing"),
        Entry("commonmark-java", "BSD-2-Clause", "Version 0.30.1 (including tables and strikethrough extensions), parses Markdown replies for display. Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
        Entry("xterm.js", "MIT", "Version 5.5.0 (and @xterm/addon-fit 0.10.0), terminal emulator for the built-in terminal screen. https://github.com/xtermjs/xterm.js"),
        Entry("Shizuku API", "Apache-2.0", "Version 13.1.5 (api and provider), binds a local helper service via official APIs only when Shizuku is installed, running, and authorized. https://github.com/RikkaApps/Shizuku-API"),
    )
}

/**
 * 许可证页(关于页底部「Apache-2.0 开源许可」进来):先是许可证原文(assets/licenses/Apache-2.0.txt),
 * 下面列出随 Djini 发布的第三方组件,一个组件一行(名称、许可证),说明在行下面;页脚指向完整文本。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Djini" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            DjiniCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = DjiniType.Micro.copy(fontFamily = FontFamily.Monospace),
                    color = DjiniTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = DjiniType.Hint, color = DjiniTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
