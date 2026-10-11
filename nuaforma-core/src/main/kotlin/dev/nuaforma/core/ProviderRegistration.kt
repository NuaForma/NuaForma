package dev.nuaforma.core

import dev.nuaforma.contracts.DocumentProvider
import dev.nuaforma.contracts.ProviderCapabilities
import dev.nuaforma.contracts.ProviderMetadata
import java.util.function.Supplier

/** Supply an uninvoked factory, not an already initialized provider, for pre-initialization validation. */
class ProviderRegistration(val metadata: ProviderMetadata, val factory: Supplier<DocumentProvider>)

class RegisteredProvider internal constructor(
    val metadata: ProviderMetadata,
    val capabilities: ProviderCapabilities,
    val provider: DocumentProvider,
)
