package io.github.cragcoffee.memoripple.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.cragcoffee.memoripple.ui.components.ProductSettingsRow
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OssLicenseCatalog(
    val schemaVersion: Int,
    val generatedFrom: String,
    val componentCount: Int,
    val directComponentCount: Int,
    val transitiveComponentCount: Int,
    val libraries: List<OssLibrary>,
    val nonOpenSourceTerms: List<OssNonOpenSourceTerms>,
    val runtimeComponents: List<OssRuntimeComponent>,
)

@Serializable
internal data class OssLibrary(
    val id: String,
    val name: String,
    val licenseName: String,
    val licenseIdentifier: String? = null,
    val notice: String,
    val licenseTextAsset: String,
    val components: List<String>,
)

@Serializable
internal data class OssNonOpenSourceTerms(
    val coordinate: String,
    val termsName: String,
    val termsUrl: String,
    val metadataSource: String,
)

@Serializable
internal data class OssRuntimeComponent(
    val coordinate: String,
    val dependencyKind: String,
    val licenseIdentifier: String,
    val licenseName: String,
    val licenseUrl: String,
    val licenseSource: String,
    val displayEntryId: String? = null,
)

internal object OssLicenseAssetRepository {
    private const val CATALOG_ASSET = "oss/oss_licenses.json"
    private const val ASSET_ROOT = "oss/"
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeCatalog(value: String): OssLicenseCatalog = json.decodeFromString(value)

    fun loadCatalog(context: Context): OssLicenseCatalog = context.assets.open(CATALOG_ASSET)
        .bufferedReader()
        .use { decodeCatalog(it.readText()) }

    fun loadLicenseText(context: Context, asset: String): String {
        require(!asset.startsWith('/') && ".." !in asset) { "Invalid license asset path" }
        return context.assets.open(ASSET_ROOT + asset).bufferedReader().use { it.readText() }
    }
}

@Composable
fun OssLicenseListScreen(
    onBack: () -> Unit,
    onOpenLicense: (String) -> Unit,
) {
    val context = LocalContext.current
    val catalog = remember(context) { runCatching { OssLicenseAssetRepository.loadCatalog(context) } }
    Scaffold(
        topBar = { ProductTopBar(title = "オープンソースライセンス", onBack = onBack) },
    ) { padding ->
        catalog.fold(
            onSuccess = { value ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .testTag("oss_license_list"),
                    contentPadding = PaddingValues(vertical = ProductSpacing.md),
                ) {
                    item {
                        Text(
                            text = "MemoRippleが利用しているオープンソースソフトウェアと" +
                                "ライセンスを確認できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = ProductSpacing.xl,
                                vertical = ProductSpacing.md,
                            ),
                        )
                    }
                    items(value.libraries, key = OssLibrary::id) { library ->
                        ProductSettingsRow(
                            title = library.name,
                            supportingText = library.licenseName,
                            testTag = "oss_license_${library.id}",
                            onClick = { onOpenLicense(library.id) },
                        )
                    }
                }
            },
            onFailure = {
                OssAssetError(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    text = "ライセンス情報を読み込めませんでした。",
                )
            },
        )
    }
}

@Composable
fun OssLicenseDetailScreen(
    licenseId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val content = remember(context, licenseId) {
        runCatching {
            val library = OssLicenseAssetRepository.loadCatalog(context)
                .libraries
                .single { it.id == licenseId }
            library to OssLicenseAssetRepository.loadLicenseText(
                context,
                library.licenseTextAsset,
            )
        }
    }
    Scaffold(
        topBar = { ProductTopBar(title = "ライセンス詳細", onBack = onBack) },
    ) { padding ->
        content.fold(
            onSuccess = { (library, licenseText) ->
                val chunks = remember(licenseText) { licenseText.chunkedForDisplay() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .testTag("oss_license_detail"),
                    contentPadding = PaddingValues(ProductSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ProductSpacing.lg),
                ) {
                    item {
                        Text(
                            text = library.name,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    item {
                        Text(
                            text = library.licenseName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        Text(
                            text = library.notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(chunks) { index, chunk ->
                        Text(
                            text = chunk,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("oss_license_text_$index"),
                        )
                    }
                }
            },
            onFailure = {
                OssAssetError(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    text = "ライセンス情報を読み込めませんでした。",
                )
            },
        )
    }
}

@Composable
private fun OssAssetError(modifier: Modifier, text: String) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(ProductSpacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun String.chunkedForDisplay(maxChars: Int = 8_000): List<String> {
    return chunked(maxChars)
}
