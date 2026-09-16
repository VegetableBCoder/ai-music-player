package com.aimusic.player.llm.parse

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.llm.LlmFailureKind
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormalizeParserTest {

    private val categories = listOf("音乐类型", "情绪", "场景", "主题")
    private val logger = RecordingLogger()
    private val parser = NormalizeParser(maxTagsPerCategory = 2, logger = logger)

    private fun request(fileName: String) =
        NormalizeRequest(fileName, metadata = null, categories = categories, tags = emptyList())

    private fun succeed(outcome: NormalizeOutcome): NormalizeResult {
        assertThat(outcome).isInstanceOf(NormalizeOutcome.Success::class.java)
        return (outcome as NormalizeOutcome.Success).result
    }

    private fun kindOf(outcome: NormalizeOutcome): LlmFailureKind {
        assertThat(outcome).isInstanceOf(NormalizeOutcome.Failure::class.java)
        return (outcome as NormalizeOutcome.Failure).kind
    }

    private fun item(index: Int, title: String = "标题$index") =
        """{"file_index":$index,"canonical_title":"$title","artists":["歌手$index"],"tag_groups":[]}"""

    @Test
    fun `乱序返回按 file_index 对回文件（不用文件名做键）`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(3, "三号")},${item(1, "一号")},${item(2, "二号")}]}"""

        val out = parser.parse(json, reqs)

        assertThat(out).hasSize(3)
        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("一号")
        assertThat(succeed(out[1]).canonicalTitle).isEqualTo("二号")
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("三号")
    }

    @Test
    fun `整批不是 JSON → 整批 INVALID_OUTPUT`() {
        val reqs = listOf(request("a.flac"), request("b.flac"))
        val out = parser.parse("not json", reqs)
        assertThat(out).hasSize(2)
        assertThat(out.map(::kindOf)).containsExactly(LlmFailureKind.INVALID_OUTPUT, LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `缺 results → 整批 INVALID_OUTPUT`() {
        val out = parser.parse("""{"foo":1}""", listOf(request("a.flac")))
        assertThat(kindOf(out.single())).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `results 非数组 → 整批 INVALID_OUTPUT`() {
        val out = parser.parse("""{"results":{}}""", listOf(request("a.flac")))
        assertThat(kindOf(out.single())).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `模型少回一项 → 该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(1)},${item(3)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("标题3")
        assertThat(logger.warnings.any { it.contains("长度") }).isTrue()
    }

    @Test
    fun `重复 file_index → 该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(1)},${item(2, "甲")},${item(2, "乙")},${item(3)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("标题3")
    }

    @Test
    fun `越界 file_index → 忽略该条，不算失败、不影响其余`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[${item(1)},${item(2)},${item(99)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(out).hasSize(2)
        assertThat(out[0]).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(out[1]).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(logger.warnings.any { it.contains("越界") }).isTrue()
    }

    @Test
    fun `缺 file_index 的条目被忽略，对应文件判失败`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[{"canonical_title":"无索引","artists":["x"],"tag_groups":[]},${item(1)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `标题空 → 只该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[${item(1, "")},${item(2)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(kindOf(out[0])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[1]).canonicalTitle).isEqualTo("标题2")
    }

    @Test
    fun `artists 缺失或空数组 → 该文件失败`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[
            {"file_index":1,"canonical_title":"甲","artists":[],"tag_groups":[]},
            {"file_index":2,"canonical_title":"乙","tag_groups":[]}]}"""

        val out = parser.parse(json, reqs)

        assertThat(kindOf(out[0])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `artists 按分隔符拆分、归一、去重且不排序`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"小酒窝","artists":["林俊杰 & 蔡卓妍","林俊杰"],"tag_groups":[]}]}"""
        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())
        assertThat(result.artists).containsExactly("林俊杰", "蔡卓妍").inOrder()
    }

    @Test
    fun `围栏与前后文包裹时仍能解析`() {
        val reqs = listOf(request("1.flac"))
        val fenced = "```json\n{\"results\":[${item(1, "围栏")}]}\n```"
        val prose = "好的：\n{\"results\":[${item(1, "散文")}]}\n以上。"

        assertThat(succeed(parser.parse(fenced, reqs).single()).canonicalTitle).isEqualTo("围栏")
        assertThat(succeed(parser.parse(prose, reqs).single()).canonicalTitle).isEqualTo("散文")
    }

    @Test
    fun `tag_groups 缺失视为合法，得到空标签`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"]}]}"""
        assertThat(succeed(parser.parse(json, listOf(request("a.flac"))).single()).tagAssignments).isEmpty()
    }
}