package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class MetadataTest {
    private val text = """
        provider.id=example.docx
        provider.class=example.DocxProvider
        provider.version=2.3.4
        contract.major=1
        contract.minor=2
    """.trimIndent()

    @ParameterizedTest
    @CsvSource("0.2,0.1,true", "0.2,0.2,true", "0.1,0.2,false", "0.2.9,0.2,true", "0.2,0.3,false", "1.2.0,1.2,true", "1.2.7,1.2,true", "1.9,1.2,true", "1.1,1.2,false", "2.0,1.2,false", "1.2,1.3,false", "1.10,1.9,true")
    fun `compatibility is same major and minimum numeric minor ignoring patch`(available: String, required: String, accepted: Boolean) {
        assertEquals(accepted, ContractVersion.parse(available).accepts(ContractVersion.parse(required)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1", "1.02", "-1.2", "1.2.3.4", "1.2-SNAPSHOT", "2147483648.1"])
    fun `malformed contract versions are rejected`(value: String) {
        assertThrows(IllegalArgumentException::class.java) { ContractVersion.parse(value) }
    }

    @Test
    fun `metadata is bounded strict and independent of provider loading`() {
        val input = object : java.io.ByteArrayInputStream(text.toByteArray()) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val metadata = ProviderMetadata.read(input)
        assertFalse(input.closed)
        assertEquals("example.docx", metadata.id)
        assertEquals("example.DocxProvider", metadata.implementationClass)
        assertEquals("2.3.4", metadata.releaseVersion)
        assertEquals(ContractVersion(1, 2), metadata.requiredContract)
        assertEquals("META-INF/nuaforma/providers/example.DocxProvider.properties", ProviderMetadata.resourcePath("example.DocxProvider"))
        assertThrows(IllegalArgumentException::class.java) { ProviderMetadata.read((text + "\ncontract.minor=3").byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ProviderMetadata.read(text.replace("contract.minor=2", "").byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ProviderMetadata.read((text + "\nunknown=true").byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderMetadata.read(text.replace("contract.minor=2", "contract.minor=02").byteInputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderMetadata.read((" ".repeat(8193)).byteInputStream())
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderMetadata.resourcePath("../Provider") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.0.0", "0.1.0-SNAPSHOT", "1.2.3-beta.1+build.42", "10.20.30+001"])
    fun `provider release version is independent valid SemVer`(value: String) {
        val metadata = ProviderMetadata("example", "example.Provider", value, ContractVersion(1, 2))
        assertEquals(value, metadata.releaseVersion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.2", "01.2.3", "1.2.3-01", "1.2.3-a..b", "1.2.3+", "1.2.3-"])
    fun `invalid provider versions fail clearly`(value: String) {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderMetadata("example", "example.Provider", value, ContractVersion(1, 2))
        }
    }
}
