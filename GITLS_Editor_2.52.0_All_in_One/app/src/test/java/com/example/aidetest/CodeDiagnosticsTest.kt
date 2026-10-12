package com.example.aidetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeDiagnosticsTest {
    private fun issues(text: String, mode: String) = CodeDiagnostics.analyze(text, mode)
    private fun fixed(text: String, mode: String) =
        CodeDiagnostics.applyAllFixes(text, CodeDiagnostics.analyze(text, mode)).first

    @Test fun validJsHasNoIssues() {
        assertTrue(issues("const a = [1, 2];\nfunction f(x) { return x * 2; }\n", "js").isEmpty())
    }

    @Test fun regexStringsAndCommentsAreIgnored() {
        assertTrue(issues("const r = /[(]+/g;\nconst s = '(';\n// ) komentar\n", "js").isEmpty())
    }

    @Test fun jsMissingParenIsInsertedBeforeSemicolon() {
        val src = "function a() {\n  console.log(\"hi\";\n}\n"
        val found = issues(src, "js")
        assertEquals(1, found.size)
        assertEquals(2, found[0].line)
        assertEquals("function a() {\n  console.log(\"hi\");\n}\n", fixed(src, "js"))
    }

    @Test fun jsUnclosedBraceAtEnd() {
        assertEquals("function f() {\n  return 1;\n}", fixed("function f() {\n  return 1;\n", "js"))
    }

    @Test fun jsUnclosedStringIsClosedBeforeSemicolon() {
        val src = "const s = \"abc;\nlet x = 1;\n"
        assertEquals(1, issues(src, "js").size)
        assertEquals("const s = \"abc\";\nlet x = 1;\n", fixed(src, "js"))
    }

    @Test fun jsonValidHasNoIssues() {
        assertTrue(issues("{\"a\": [1, 2, {\"b\": null}], \"c\": true}", "json").isEmpty())
    }

    @Test fun jsonTrailingCommaRemoved() {
        assertEquals("{\n  \"a\": 1\n}\n", fixed("{\n  \"a\": 1,\n}\n", "json"))
    }

    @Test fun jsonMissingCommaAdded() {
        val src = "{\n  \"a\": 1\n  \"b\": 2\n}"
        val found = issues(src, "json")
        assertEquals(1, found.size)
        assertEquals(2, found[0].line)
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", fixed(src, "json"))
    }

    @Test fun jsonSingleQuotesAndUnquotedKey() {
        assertEquals("{\"name\": \"Budi\"}", fixed("{name: 'Budi'}", "json"))
    }

    @Test fun jsonPythonLiteralsReplaced() {
        assertEquals("{\"a\": true, \"b\": null}", fixed("{\"a\": True, \"b\": None}", "json"))
    }

    @Test fun jsonUnclosedBracketsClosedInnermostFirst() {
        assertEquals("{\"a\": [1, 2\n]}", fixed("{\"a\": [1, 2", "json"))
    }

    @Test fun htmlUnclosedSpanClosedBeforeParent() {
        assertEquals("<div><span>hi</span></div>", fixed("<div><span>hi</div>", "html"))
    }

    @Test fun htmlStrayClosingTagRemoved() {
        assertEquals("<div></div>", fixed("<div></div></span>", "html"))
    }

    @Test fun htmlOptionalEndTagsAreNotErrors() {
        assertTrue(issues("<ul><li>a<li>b</ul>", "html").isEmpty())
    }

    @Test fun htmlImgWithoutAltGetsWarningFix() {
        val found = issues("<img src=\"a.png\">", "html")
        assertEquals(CodeDiagnostics.Severity.WARNING, found[0].severity)
        assertEquals("<img src=\"a.png\" alt=\"\">", fixed("<img src=\"a.png\">", "html"))
    }

    @Test fun htmlInlineScriptIsChecked() {
        val found = issues("<script>\nfunction f() {\n</script>", "html")
        assertEquals(1, found.size)
        assertEquals(2, found[0].line)
    }

    @Test fun unsupportedModeReturnsNothing() {
        assertTrue(issues("def x(:", "py").isEmpty())
    }
}
