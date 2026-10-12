// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import com.mixradio.droid.data.syntax.SyntaxPackStore
import com.mixradio.droid.ui.components.SyntaxPackUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语法包下载源的契约。
 *
 * 「从仓库下载」预填的直链曾只有 `raw.githubusercontent.com`：国内网络访问它常超时或被重置，
 * 而语法包是拿到语法着色的唯一途径 —— 唯一入口不可达等于该功能在目标网络环境下不可用。
 * 现提供两源（Gitee / GitHub），须保持「Gitee 在前」且两源指向同一 tag 下的同一份文件，
 * 否则备用源会悄悄给出不同产物。
 */
class SyntaxPackSourceTest {

    @Test
    fun `首选源必须是 Gitee 且预填它`() {
        assertEquals("gitee", SyntaxPackUrls.SOURCES.first().first)
        assertEquals(SyntaxPackUrls.SOURCES.first().second, SyntaxPackUrls.PACK_ZIP)
    }

    @Test
    fun `两源必须同 tag 同文件且为 https`() {
        assertTrue("至少两个源", SyntaxPackUrls.SOURCES.size >= 2)
        assertTrue("必须有 GitHub 备用源", SyntaxPackUrls.SOURCES.any { it.first == "github" })
        val tags = SyntaxPackUrls.SOURCES.map { (_, url) ->
            assertTrue("$url 必须为 https", url.startsWith("https://"))
            // 两种直链只差 tag 的前缀（Gitee `/raw/<tag>/x`、GitHub `/refs/tags/<tag>/x`），
            // 文件名前的那一段即为 tag；两源 tag 不同就会拿到不同产物。
            val path = url.substringBefore('?').trimEnd('/')
            assertTrue("$url 必须指向 syntax-packs.zip", path.endsWith("/syntax-packs.zip"))
            path.removeSuffix("/syntax-packs.zip").substringAfterLast('/')
        }
        assertEquals("两源必须指向同一 tag", 1, tags.toSet().size)
    }

    @Test
    fun `备用直链为 GitHub 且来源标签按直链归一`() {
        assertEquals("github", SyntaxPackUrls.SOURCES.last().first)
        assertEquals(SyntaxPackUrls.SOURCES.last().second, SyntaxPackUrls.PACK_ZIP_ALT)
        assertEquals("gitee", SyntaxPackUrls.sourceLabel(SyntaxPackUrls.PACK_ZIP))
        assertEquals("github", SyntaxPackUrls.sourceLabel(SyntaxPackUrls.PACK_ZIP_ALT))
        // 本地文件导入的 source 是路径，原样显示
        assertEquals("/sdcard/Download/x.zip", SyntaxPackUrls.sourceLabel("/sdcard/Download/x.zip"))
    }

    @Test
    fun `直链推断语法标识必须先剥 query 再取末段`() {
        // query 自身含斜杠时（带签名的下载链、带路径参数的网关），先取末段会取到 query 里的片段，
        // 语法就以该片段为 id 落盘，编辑器不会用它匹配 .kt 一类文件 —— 导入成功却不高亮。
        assertEquals("kotlin", SyntaxPackStore.idFromUrl("https://host/a/kotlin.json?p=a/b"))
        assertEquals("kotlin", SyntaxPackStore.idFromUrl("https://host/kotlin.json?sig=x/y&t=1"))
        // fragment 同理
        assertEquals("kotlin", SyntaxPackStore.idFromUrl("https://host/kotlin.json#/frag/ment"))
        // 常规形态不受影响
        assertEquals("kotlin", SyntaxPackStore.idFromUrl("https://host/x/kotlin.json"))
        assertEquals("kotlin", SyntaxPackStore.idFromUrl("https://host/kotlin.json?v=2"))
    }
}
