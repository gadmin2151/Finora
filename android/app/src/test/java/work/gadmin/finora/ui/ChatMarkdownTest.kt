package work.gadmin.finora.ui

import androidx.compose.ui.graphics.Color
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun allowsOnlySafeBrowserLinks() {
        assertEquals(
            "https://example.test/items?q=1#details",
            safeChatLink("https://example.test/items?q=1#details"),
        )
        listOf(
                "javascript:alert(1)",
                "data:text/html,hello",
                "file:///data/local",
                "intent://test",
                "//example.test",
                "https://user:password@example.test",
                "https://example.test\\evil",
                "https://example.test\n",
            )
            .forEach {
                assertNull(it, safeChatLink(it))
            }
    }

    @Test
    fun formatsInlineMarkdownWithoutRawHtmlOrUnsafeLinkAnnotations() {
        val node =
            Parser.builder()
                .build()
                .parse(
                    "**Total** *today*: `12` [details](https://example.test) [bad](javascript:alert) <img src=x>"
                )
                .firstChild
        val result = markdownInline(node, Color.Black)
        assertEquals("Total today: 12 details bad ", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
        assertEquals(1, result.getLinkAnnotations(0, result.length).size)
    }
}
