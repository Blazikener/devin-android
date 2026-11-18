package com.blazikener.devin.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesEveryHeadingLevel() {
        val blocks = MarkdownParser.parse((1..6).joinToString("\n") { level -> "${"#".repeat(level)} Heading $level" })

        assertEquals(
            (1..6).map { MdBlock.Heading(it, "Heading $it") },
            blocks,
        )
    }

    @Test
    fun parsesFencedCodeWithLanguageAndUnterminatedFence() {
        assertEquals(
            listOf(MdBlock.Code("kotlin", "println(\"hello\")")),
            MarkdownParser.parse("```kotlin\nprintln(\"hello\")\n```"),
        )
        assertEquals(
            listOf(MdBlock.Code("python", "print('hello')")),
            MarkdownParser.parse("```python\nprint('hello')"),
        )
    }

    @Test
    fun parsesBulletsNestedIndentAndNumberedMarkers() {
        assertEquals(
            listOf(
                MdBlock.Bullet(0, "•", "parent"),
                MdBlock.Bullet(1, "•", "nested"),
                MdBlock.Bullet(0, "1.", "first"),
                MdBlock.Bullet(0, "2.", "second"),
            ),
            MarkdownParser.parse("- parent\n  - nested\n1. first\n2) second"),
        )
    }

    @Test
    fun parsesQuotesAndRules() {
        assertEquals(
            listOf(MdBlock.Quote("quoted text"), MdBlock.Rule),
            MarkdownParser.parse("> quoted text\n---"),
        )
    }

    @Test
    fun joinsParagraphLines() {
        assertEquals(
            listOf(MdBlock.Paragraph("first line\nsecond line")),
            MarkdownParser.parse("first line\nsecond line"),
        )
    }

    @Test
    fun blankLinesSplitParagraphs() {
        assertEquals(
            listOf(MdBlock.Paragraph("first"), MdBlock.Paragraph("second")),
            MarkdownParser.parse("first\n\nsecond"),
        )
    }

    @Test
    fun plainStripsMarkdownForNotifications() {
        assertEquals(
            "I've received your request: Please continue\n" +
                "Use ApiException\n" +
                "• Retry the docs\n" +
                "val x = 1",
            MarkdownParser.plain("I've received your request: **Please continue**\n\nUse `ApiException`\n- Retry [the docs](https://x.y)\n```kotlin\nval x = 1\n```"),
        )
    }
}
