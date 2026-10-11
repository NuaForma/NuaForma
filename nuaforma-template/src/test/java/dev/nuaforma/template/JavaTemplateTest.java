package dev.nuaforma.template;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaTemplateTest {
    @Test
    void javaCanUseStructuralSessionsAndSourceFragments() throws Exception {
        TemplateSession session = TemplateSession.open(Map.of("rows", java.util.List.of("Ada")));
        TemplateSession row = session.repeat("rows", "row").get(0);
        TemplateFragment fragment = row.expand("{row}").get(0);
        assertEquals("Ada", fragment.getText());
        assertEquals(0, fragment.getSourceStart());
        assertEquals(5, fragment.getSourceEnd());
        assertFalse(fragment.getLiteral());
        assertTrue(row.test("loop.first"));
    }
    @Test
    void javaCanCompileAndRenderWithoutKotlinSpecificArguments() throws Exception {
        CompiledTemplate template = Templates.compile("Hello {name}{#if active}!{/if}");
        assertEquals("Hello Ada!", template.render(Map.of("name", "Ada", "active", true)));
        assertEquals(17, Runtime.version().feature());
    }

    @Test
    void runtimeDoesNotPullInCoreDocxOrServer() {
        for (String name : new String[] {"dev.nuaforma.core.ProviderRegistry", "org.docx4j.wml.Document", "dev.nuaforma.docx.DocxDocuments"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
        }
    }
}
