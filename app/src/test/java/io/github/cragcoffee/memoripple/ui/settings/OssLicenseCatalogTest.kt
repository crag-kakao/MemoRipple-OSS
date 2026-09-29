package io.github.cragcoffee.memoripple.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OssLicenseCatalogTest {
    @Test
    fun committedCatalogCoversEveryResolvedReleaseRuntimeComponent() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )

        assertEquals(1, catalog.schemaVersion)
        assertEquals(":app:releaseRuntimeClasspath", catalog.generatedFrom)
        assertEquals(202, catalog.componentCount)
        // materialkolor joined for テーマカラー/パレットスタイル scheme generation.
        assertEquals(19, catalog.directComponentCount)
        assertEquals(183, catalog.transitiveComponentCount)
        assertEquals(catalog.componentCount, catalog.runtimeComponents.size)
        assertEquals(
            catalog.runtimeComponents.size,
            catalog.runtimeComponents.map { it.coordinate }.distinct().size,
        )
        assertEquals(7, catalog.nonOpenSourceTerms.size)
        assertTrue(
            catalog.nonOpenSourceTerms.all {
                it.coordinate.startsWith("com.google.android.gms:") &&
                    it.termsName.contains("Android Software Development Kit License")
            },
        )

        val entryIds = catalog.libraries.map { it.id }.toSet()
        catalog.runtimeComponents.forEach { component ->
            if (component.displayEntryId == null) {
                assertEquals("ANDROID-SDK-LICENSE", component.licenseIdentifier)
            } else {
                assertTrue(component.displayEntryId in entryIds)
            }
        }
    }

    @Test
    fun listIsStableAndEveryReferencedLicenseAssetIsOfflineAndNonEmpty() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )

        assertEquals(catalog.libraries.size, catalog.libraries.map { it.id }.distinct().size)
        assertEquals(
            catalog.libraries.sortedBy { it.name.lowercase() }.map { it.id },
            catalog.libraries.map { it.id },
        )
        catalog.libraries.forEach { library ->
            val asset = projectFile("app/src/main/assets/oss/${library.licenseTextAsset}")
            assertTrue("Missing asset for ${library.id}", asset.isFile)
            assertTrue("Empty asset for ${library.id}", asset.length() > 100L)
            assertTrue(library.components.isNotEmpty())
            assertNotNull(library.notice)
        }
    }

    /**
     * The Local AI runtime is native code outside the Gradle graph (libmemoripple_llm.so from the pinned
     * llama.cpp submodule, and the NDK's libc++_shared.so it needs): its notices are hand-reviewed entries,
     * and the committed texts must carry the submodule's own licence files verbatim, so a submodule bump
     * that changes them fails here (docs/OSS_LICENSES.md, native runtime).
     */
    @Test
    fun theNativeLocalAiRuntimeCarriesItsLicenseNotices() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )
        val llama = catalog.libraries.single { it.id == "llama-cpp" }
        assertEquals("MIT", llama.licenseIdentifier)
        val llamaText = projectFile("app/src/main/assets/oss/${llama.licenseTextAsset}").readText()
        val submodule = "llmbench/src/main/cpp/llama.cpp"
        assertTrue("llama.cpp's own LICENSE, verbatim", llamaText.contains(projectFile("$submodule/LICENSE").readText().trim()))
        assertTrue("the bundled nlohmann/json licence, verbatim", llamaText.contains(projectFile("$submodule/licenses/LICENSE-jsonhpp").readText().trim()))
        assertTrue("the YaRN attribution carried in ggml-cpu", llamaText.contains("Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng"))
        assertTrue("the tokenizer's ggllm.cpp origin", llamaText.contains("cmp-nct/ggllm.cpp"))

        val libcxx = catalog.libraries.single { it.id == "llvm-libcxx" }
        assertEquals("Apache-2.0 WITH LLVM-exception", libcxx.licenseIdentifier)
        val libcxxText = projectFile("app/src/main/assets/oss/${libcxx.licenseTextAsset}").readText()
        assertTrue(libcxxText.contains("---- LLVM Exceptions to the Apache 2.0 License ----"))
        assertTrue(libcxxText.contains("Apache License"))
    }

    /**
     * llama.cpp's src/unicode-data.cpp is generated from the Unicode Character Database and linked into
     * libmemoripple_llm.so: its Unicode License v3 notice is the NDK's own copy, verbatim (docs/OSS_LICENSES.md).
     */
    @Test
    fun theUnicodeCharacterDatabaseTablesCarryTheUnicodeLicenseV3() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )
        val unicode = catalog.libraries.single { it.id == "unicode-data" }
        assertEquals("Unicode Character Database", unicode.name)
        assertEquals("Unicode License v3", unicode.licenseName)
        assertEquals("Unicode-3.0", unicode.licenseIdentifier)
        val text = projectFile("app/src/main/assets/oss/${unicode.licenseTextAsset}").readText()
        assertTrue(text.isNotBlank())
        assertTrue(text.startsWith("UNICODE LICENSE V3\n\nCOPYRIGHT AND PERMISSION NOTICE\n"))
        assertTrue(text.contains("Copyright © 2016-2023 Unicode, Inc."))
        assertTrue(text.trimEnd().endsWith("authorization of the copyright holder."))
        // the other native notices stay
        assertTrue(catalog.libraries.any { it.id == "llama-cpp" })
        assertTrue(catalog.libraries.any { it.id == "llvm-libcxx" })
    }

    /** What a library is shown under agrees with what its artifacts' own POMs declare (2026-09-28: MaterialKolor was shown as Apache-2.0 while its POMs say MIT). */
    @Test
    fun everyLibraryIsShownUnderTheLicenseItsArtifactsDeclare() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )
        val byEntry = catalog.runtimeComponents.filter { it.displayEntryId != null }.groupBy { it.displayEntryId }
        catalog.libraries.forEach { library ->
            val declared = byEntry[library.id].orEmpty().map { it.licenseIdentifier }.toSet()
            if (declared.isNotEmpty()) assertEquals("${library.id} shown as it is declared", setOf(library.licenseIdentifier), declared)
        }
    }

    /** MaterialKolor 4.0.5 is MIT (its POMs); the notice is the project's LICENSE at tag 4.0.5, verbatim, copyright line included. */
    @Test
    fun materialKolorIsShownUnderMitWithItsOwnCopyrightAndPermissionNotice() {
        val catalog = OssLicenseAssetRepository.decodeCatalog(
            projectFile("app/src/main/assets/oss/oss_licenses.json").readText(),
        )
        val kolor = catalog.libraries.single { it.id == "materialkolor" }
        assertEquals("The MIT License", kolor.licenseName)
        assertEquals("MIT", kolor.licenseIdentifier)
        assertEquals(
            listOf(
                "com.materialkolor:material-color-utilities-android:4.0.5",
                "com.materialkolor:material-color-utilities:4.0.5",
                "com.materialkolor:material-kolor-android:4.0.5",
                "com.materialkolor:material-kolor:4.0.5",
            ),
            kolor.components,
        )
        val text = projectFile("app/src/main/assets/oss/${kolor.licenseTextAsset}").readText()
        assertTrue(text.startsWith("MIT License\n\nCopyright (c) 2025 Jordon de Hoog\n"))
        assertTrue(text.contains("The above copyright notice and this permission notice shall be included in all\ncopies or substantial portions of the Software."))
        assertTrue(text.contains("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND"))
    }

    @Test(expected = Exception::class)
    fun malformedCatalogIsRejected() {
        OssLicenseAssetRepository.decodeCatalog("{not valid json")
    }

    private fun projectFile(relativePath: String): File {
        return sequenceOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::exists)
            ?: error("Project file not found: $relativePath")
    }
}
