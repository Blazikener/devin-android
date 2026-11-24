package com.blazikener.devin.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
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

    @Test
    fun linksKeepBalancedParenthesesInDestination() {
        val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"
        val nested = "https://example.com/foo_(a(b)c).html"
        val rendered = MarkdownParser.inline("See [Wiki]($wiki), then [Example]($nested).", Color.Unspecified, Color.Unspecified)

        assertEquals("See Wiki, then Example.", rendered.text)
        assertEquals(
            listOf("Wiki" to wiki, "Example" to nested),
            rendered.getLinkAnnotations(0, rendered.length).map {
                rendered.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
            },
        )
    }

    @Test
    fun destinationsUnescapePunctuationWithoutChangingParenthesisDepth() {
        val rendered = MarkdownParser.inline(
            """[open](https://a.com/\(foo) ![close](https://a.com/foo\).png) [slash](https://a.com/\\(foo)\q\!)""",
            Color.Unspecified,
            Color.Unspecified,
        )

        assertEquals("open close slash", rendered.text)
        assertEquals(
            listOf("https://a.com/(foo", "https://a.com/foo).png", """https://a.com/\(foo)\q!"""),
            rendered.getLinkAnnotations(0, rendered.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun imagesUseAltTextAsClickableLabel() {
        val url = "https://example.com/chart_(final).png"
        val markdown = "See ![Final chart]($url) and ![]($url)."
        val rendered = MarkdownParser.inline(markdown, Color.Unspecified, Color.Unspecified)

        assertEquals("See Final chart and Image.", rendered.text)
        assertEquals(rendered.text, MarkdownParser.plain(markdown))
        assertEquals(
            listOf("Final chart" to url, "Image" to url),
            rendered.getLinkAnnotations(0, rendered.length).map {
                rendered.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
            },
        )
    }

    @Test
    fun linkedImageUsesAltTextAndOpensOuterDestination() {
        val rendered = MarkdownParser.inline(
            "See [![Chart](https://example.com/image.png)](https://example.com/report).",
            Color.Unspecified,
            Color.Unspecified,
        )

        assertEquals("See Chart.", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single()
        assertEquals("Chart", rendered.text.substring(link.start, link.end))
        assertEquals("https://example.com/report", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun linksAndImagesIgnoreOptionalTitles() {
        val rendered = MarkdownParser.inline(
            """[Double](https://example.com/double "look \"here\"") ![Single](https://example.com/single.png 'caption') [Paren](https://example.com/paren (caption))""",
            Color.Unspecified,
            Color.Unspecified,
        )

        assertEquals("Double Single Paren", rendered.text)
        assertEquals(
            listOf("https://example.com/double", "https://example.com/single.png", "https://example.com/paren"),
            rendered.getLinkAnnotations(0, rendered.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun linksAndImagesKeepAngleBracketedDestinationsWithoutBrackets() {
        val rendered = MarkdownParser.inline(
            """[Report](<https://example.com/a report>) ![Graph](<https://example.com/graph (final).png> "caption")""",
            Color.Unspecified,
            Color.Unspecified,
        )

        assertEquals("Report Graph", rendered.text)
        assertEquals(
            listOf("https://example.com/a report", "https://example.com/graph (final).png"),
            rendered.getLinkAnnotations(0, rendered.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }

    @Test
    fun escapedBangStaysLiteralBesideAnOrdinaryLink() {
        val rendered = MarkdownParser.inline(
            """wow\![x](https://example.com/report) \* literal \q `\!`""",
            Color.Unspecified,
            Color.Unspecified,
        )

        assertEquals("""wow!x * literal \q \!""", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single()
        assertEquals("x", rendered.text.substring(link.start, link.end))
        assertEquals("https://example.com/report", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun unclosedDestinationDoesNotConsumeFollowingLink() {
        val rendered = MarkdownParser.inline("[broken](foo(bar) then [valid](path)", Color.Unspecified, Color.Unspecified)

        assertEquals("[broken](foo(bar) then valid", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single()
        assertEquals("path", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun parsesTables() {
        assertEquals(
            listOf(
                MdBlock.Paragraph("intro"),
                MdBlock.Table(listOf("A", "B"), listOf(listOf("1", "`x`"), listOf("2", ""))),
                MdBlock.Paragraph("after"),
            ),
            MarkdownParser.parse("intro\n| A | B |\n| --- | :-: |\n| 1 | `x` |\n| 2 |\n\nafter"),
        )
    }

    @Test
    fun escapedPipesStayInCell() {
        assertEquals(
            listOf(MdBlock.Table(listOf("Op", "Meaning"), listOf(listOf("a | b", "or")))),
            MarkdownParser.parse("| Op | Meaning |\n|---|---|\n| a \\| b | or |"),
        )
    }

    @Test
    fun pipeLineWithoutSeparatorIsParagraph() {
        assertEquals(listOf(MdBlock.Paragraph("| not a table |")), MarkdownParser.parse("| not a table |"))
    }

    @Test
    fun joinsConsecutiveQuoteLines() {
        assertEquals(listOf(MdBlock.Quote("one\ntwo")), MarkdownParser.parse("> one\n> two"))
    }

    @Test
    fun plainFlattensTables() {
        assertEquals("A · B\n1 · x", MarkdownParser.plain("| A | B |\n|---|---|\n| 1 | `x` |"))
    }

    @Test
    fun plainFlattensCodeInsideEmphasis() {
        assertEquals("Use x now", MarkdownParser.plain("**Use `x` now**"))
        assertEquals("Please use bold and code", MarkdownParser.plain("**Please use **bold** and `code`**"))
        assertEquals("old y", MarkdownParser.plain("~~old `y`~~"))
    }
}
