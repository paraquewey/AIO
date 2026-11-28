package com.example.ui.theme

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.models.AppSettings
import com.example.domain.models.FontColorMode
import com.example.domain.models.TextureScaleMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class AioThemeManifest(
    val formatVersion: Int = 1,
    val exportedAtMillis: Long = 0L,
    val useLightTheme: Boolean = false,
    val fontColorMode: String = FontColorMode.ADAPTIVE.name,
    val backgroundDimmerAlpha: Float = 0.35f,
    val primaryAccentArgb: Long = 0xFF7B61FFL,
    val customBackgroundLuminance: Float = 0.04f,
    val customBackgroundAsset: String? = null,
    val cardTextureAsset: String? = null,
    val cardTextureMode: String = TextureScaleMode.CROP.name,
    val cardTextureAlpha: Float = 0.45f,
    val heroButtonTextureAsset: String? = null,
    val heroButtonTextureAlpha: Float = 0.65f,
    val actionButtonTextureAsset: String? = null,
    val actionButtonTextureAlpha: Float = 0.55f,
    val navBarTextureAsset: String? = null,
    val navBarTextureAlpha: Float = 0.60f,
    val titleFontAsset: String? = null,
    val titleFontScale: Float = 1.0f,
    val bodyFontAsset: String? = null,
    val bodyFontScale: Float = 1.0f,
    val terminalFontAsset: String? = null,
    val terminalFontScale: Float = 1.0f
)

data class ImportedImageResult(
    val filePath: String,
    val luminance: Float,
    val version: Long
)

data class ImportedFontResult(
    val filePath: String,
    val displayName: String,
    val version: Long
)

object ThemePackManager {
    private const val MAX_IMAGE_BYTES = 24L * 1024L * 1024L
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 32
    private const val MAX_ZIP_TOTAL_BYTES = 60L * 1024L * 1024L

    private val jsonCodec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun themesRootDir(context: Context): File =
        File(context.filesDir, "themes").apply { mkdirs() }

    private fun imagesDir(context: Context): File =
        File(themesRootDir(context), "images").apply { mkdirs() }

    private fun fontsDir(context: Context): File =
        File(themesRootDir(context), "fonts").apply { mkdirs() }

    private fun exportCacheDir(context: Context): File =
        File(context.cacheDir, "themes_export").apply { mkdirs() }

    fun importThemeImage(
        context: Context,
        uri: Uri,
        slotPrefix: String,
        maxDimensionPx: Int
    ): Result<ImportedImageResult> = runCatching {
        val dir = imagesDir(context)
        val version = System.currentTimeMillis()
        val tempFile = File(dir, "${slotPrefix}_tmp_${version}.img")
        val destFile = File(dir, "${slotPrefix}_${version}.jpg")

        copyUriToFile(context, uri, tempFile, MAX_IMAGE_BYTES)

        val bitmap = decodeSampledBackgroundBitmap(tempFile.absolutePath, maxDimensionPx = maxDimensionPx)
        if (bitmap == null) {
            tempFile.delete()
            error("Formato de imagen no compatible o dañado.")
        }
        val avgLum = try {
            computeBitmapAverageLuminance(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        deleteSlotFiles(dir, "${slotPrefix}_", exclude = tempFile)
        if (slotPrefix == "bg") {
            runCatching { File(context.filesDir, "custom_bg.jpg").delete() }
        }

        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }

        ImportedImageResult(
            filePath = destFile.absolutePath,
            luminance = avgLum,
            version = version
        )
    }

    fun clearThemeImage(context: Context, slotPrefix: String, currentPath: String?) {
        runCatching {
            if (!currentPath.isNullOrBlank()) {
                File(currentPath).delete()
            }
            deleteSlotFiles(imagesDir(context), "${slotPrefix}_")
            if (slotPrefix == "bg") {
                File(context.filesDir, "custom_bg.jpg").delete()
            }
        }
    }

    fun importThemeFont(
        context: Context,
        uri: Uri,
        slot: FontSlot
    ): Result<ImportedFontResult> = runCatching {
        val dir = fontsDir(context)
        val rawDisplayName = queryDisplayName(context, uri) ?: "${slot.id}.ttf"
        val sanitizedName = sanitizeFilename(rawDisplayName, defaultName = "${slot.id}.ttf")
        val lower = sanitizedName.lowercase()
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            error("Solo se admiten fuentes .ttf u .otf.")
        }

        val version = System.currentTimeMillis()
        val tempFile = File(dir, "${slot.id}_tmp_${version}")
        val destFile = File(dir, "${slot.id}_$sanitizedName")

        copyUriToFile(context, uri, tempFile, MAX_FONT_BYTES)

        if (!isValidFontFile(tempFile)) {
            tempFile.delete()
            error("El archivo seleccionado no es una fuente TrueType/OpenType válida.")
        }
        val loaded = loadSafeFontFamily(tempFile.absolutePath)
        if (loaded.fontFamily == null) {
            tempFile.delete()
            error(loaded.errorMessage ?: "No se pudo validar la fuente seleccionada.")
        }

        deleteSlotFiles(dir, "${slot.id}_", exclude = tempFile)
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }

        ImportedFontResult(
            filePath = destFile.absolutePath,
            displayName = sanitizedName,
            version = version
        )
    }

    fun clearThemeFont(context: Context, slot: FontSlot, currentPath: String?) {
        runCatching {
            if (!currentPath.isNullOrBlank()) {
                File(currentPath).delete()
            }
            deleteSlotFiles(fontsDir(context), "${slot.id}_")
        }
    }

    fun clearAllThemeAssets(context: Context) {
        runCatching {
            File(context.filesDir, "custom_bg.jpg").delete()
            imagesDir(context).listFiles()?.forEach { it.delete() }
            fontsDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    fun exportThemePackage(context: Context, settings: AppSettings): Result<File> = runCatching {
        val exportDir = exportCacheDir(context)
        exportDir.listFiles()?.forEach { old ->
            if (old.name.endsWith(".aiotheme")) {
                old.delete()
            }
        }
        val now = System.currentTimeMillis()
        val outFile = File(exportDir, "AioTunnel_${now}.aiotheme")

        val assetFiles = linkedMapOf<String, File>()
        fun stageAsset(path: String?, entryPrefix: String): String? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            if (!f.exists() || !f.isFile || f.length() <= 0L) return null
            val safeName = sanitizeFilename(f.name, "asset.bin")
            val entryName = "assets/${entryPrefix}_$safeName"
            assetFiles[entryName] = f
            return entryName
        }

        val manifest = AioThemeManifest(
            formatVersion = 1,
            exportedAtMillis = now,
            useLightTheme = settings.useLightTheme,
            fontColorMode = settings.fontColorMode.name,
            backgroundDimmerAlpha = settings.backgroundDimmerAlpha.coerceIn(0f, 0.85f),
            primaryAccentArgb = settings.primaryAccentArgb,
            customBackgroundLuminance = settings.customBackgroundLuminance.coerceIn(0f, 1f),
            customBackgroundAsset = stageAsset(settings.customBackgroundPath, "bg"),
            cardTextureAsset = stageAsset(settings.cardTexturePath, "card"),
            cardTextureMode = settings.cardTextureMode.name,
            cardTextureAlpha = settings.cardTextureAlpha.coerceIn(0f, 1f),
            heroButtonTextureAsset = stageAsset(settings.heroButtonTexturePath, "hero"),
            heroButtonTextureAlpha = settings.heroButtonTextureAlpha.coerceIn(0f, 1f),
            actionButtonTextureAsset = stageAsset(settings.actionButtonTexturePath, "action"),
            actionButtonTextureAlpha = settings.actionButtonTextureAlpha.coerceIn(0f, 1f),
            navBarTextureAsset = stageAsset(settings.navBarTexturePath, "navbar"),
            navBarTextureAlpha = settings.navBarTextureAlpha.coerceIn(0f, 1f),
            titleFontAsset = stageAsset(settings.titleFontPath, "title"),
            titleFontScale = settings.titleFontScale.coerceIn(0.80f, 1.35f),
            bodyFontAsset = stageAsset(settings.bodyFontPath, "body"),
            bodyFontScale = settings.bodyFontScale.coerceIn(0.80f, 1.35f),
            terminalFontAsset = stageAsset(settings.terminalFontPath, "terminal"),
            terminalFontScale = settings.terminalFontScale.coerceIn(0.80f, 1.35f)
        )

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
            val manifestBytes = jsonCodec.encodeToString(AioThemeManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("theme.json"))
            zos.write(manifestBytes)
            zos.closeEntry()

            val buffer = ByteArray(16 * 1024)
            assetFiles.forEach { (entryName, srcFile) ->
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(srcFile).use { fis ->
                    while (true) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
            }
        }

        outFile
    }

    fun importThemePackage(
        context: Context,
        uri: Uri,
        currentSettings: AppSettings
    ): Result<AppSettings> = runCatching {
        val stagingDir = File(context.cacheDir, "theme_import_${System.nanoTime()}").apply { mkdirs() }
        try {
            val stagingCanonical = stagingDir.canonicalFile
            var entriesProcessed = 0
            var totalUncompressed = 0L

            context.contentResolver.openInputStream(uri)?.use { rawIn ->
                ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        entriesProcessed++
                        if (entriesProcessed > MAX_ZIP_ENTRIES) {
                            error("El paquete .aiotheme excede el límite permitido de archivos.")
                        }
                        if (entry.name.isBlank()) {
                            zis.closeEntry()
                            continue
                        }
                        // Strict Zip Slip validation
                        val targetFile = File(stagingDir, entry.name).canonicalFile
                        val prefix = stagingCanonical.path + File.separator
                        if (targetFile.path != stagingCanonical.path && !targetFile.path.startsWith(prefix)) {
                            error("Archivo .aiotheme rechazado por ruta insegura (Zip Slip).")
                        }

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                            zis.closeEntry()
                            continue
                        }

                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            var entryBytes = 0L
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                entryBytes += read
                                totalUncompressed += read
                                if (entryBytes > MAX_IMAGE_BYTES || totalUncompressed > MAX_ZIP_TOTAL_BYTES) {
                                    error("El paquete .aiotheme excede el tamaño máximo permitido.")
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                        zis.closeEntry()
                    }
                }
            } ?: error("No se pudo abrir el archivo .aiotheme seleccionado.")

            val manifestFile = File(stagingDir, "theme.json")
            if (!manifestFile.exists() || !manifestFile.isFile) {
                error("Paquete .aiotheme inválido: falta theme.json.")
            }
            val manifest = jsonCodec.decodeFromString(
                AioThemeManifest.serializer(),
                manifestFile.readText(Charsets.UTF_8)
            )

            val version = System.currentTimeMillis()
            val imgDir = imagesDir(context)
            val fntDir = fontsDir(context)

            fun resolveStagedFile(relativePath: String?): File? {
                if (relativePath.isNullOrBlank()) return null
                val candidate = File(stagingDir, relativePath).canonicalFile
                val prefix = stagingCanonical.path + File.separator
                if (!candidate.path.startsWith(prefix)) {
                    error("Ruta de recurso fuera del paquete .aiotheme.")
                }
                return candidate.takeIf { it.exists() && it.isFile && it.length() > 0L }
            }

            fun installImageAsset(relativePath: String?, slotPrefix: String, maxDim: Int): Pair<String?, Float?> {
                val staged = resolveStagedFile(relativePath)
                deleteSlotFiles(imgDir, "${slotPrefix}_")
                if (slotPrefix == "bg") {
                    runCatching { File(context.filesDir, "custom_bg.jpg").delete() }
                }
                if (staged == null) return null to null
                val bmp = decodeSampledBackgroundBitmap(staged.absolutePath, maxDimensionPx = maxDim)
                    ?: error("Imagen corrupta en el paquete ($slotPrefix).")
                val lum = try {
                    computeBitmapAverageLuminance(bmp)
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
                val dest = File(imgDir, "${slotPrefix}_${version}.jpg")
                staged.copyTo(dest, overwrite = true)
                return dest.absolutePath to lum
            }

            fun installFontAsset(relativePath: String?, slot: FontSlot): String? {
                val staged = resolveStagedFile(relativePath)
                deleteSlotFiles(fntDir, "${slot.id}_")
                if (staged == null) return null
                if (!isValidFontFile(staged) || loadSafeFontFamily(staged.absolutePath).fontFamily == null) {
                    return null
                }
                val cleanOrig = sanitizeFilename(
                    staged.name.removePrefix("${slot.id}_"),
                    "${slot.id}.ttf"
                )
                val dest = File(fntDir, "${slot.id}_$cleanOrig")
                staged.copyTo(dest, overwrite = true)
                return dest.absolutePath
            }

            val (bgPath, bgLum) = installImageAsset(manifest.customBackgroundAsset, "bg", 1440)
            val (cardTexPath, _) = installImageAsset(manifest.cardTextureAsset, TextureSlot.CARD.id, TextureSlot.CARD.maxDimensionPx)
            val (heroTexPath, _) = installImageAsset(manifest.heroButtonTextureAsset, TextureSlot.HERO_BUTTON.id, TextureSlot.HERO_BUTTON.maxDimensionPx)
            val (actionTexPath, _) = installImageAsset(manifest.actionButtonTextureAsset, TextureSlot.ACTION_BUTTON.id, TextureSlot.ACTION_BUTTON.maxDimensionPx)
            val (navTexPath, _) = installImageAsset(manifest.navBarTextureAsset, TextureSlot.NAV_BAR.id, TextureSlot.NAV_BAR.maxDimensionPx)

            val titleFontPath = installFontAsset(manifest.titleFontAsset, FontSlot.TITLE)
            val bodyFontPath = installFontAsset(manifest.bodyFontAsset, FontSlot.BODY)
            val terminalFontPath = installFontAsset(manifest.terminalFontAsset, FontSlot.TERMINAL)

            val parsedFontColorMode = runCatching {
                FontColorMode.valueOf(manifest.fontColorMode)
            }.getOrDefault(FontColorMode.ADAPTIVE)
            val parsedCardMode = runCatching {
                TextureScaleMode.valueOf(manifest.cardTextureMode)
            }.getOrDefault(TextureScaleMode.CROP)

            currentSettings.copy(
                useLightTheme = manifest.useLightTheme,
                fontColorMode = parsedFontColorMode,
                backgroundDimmerAlpha = manifest.backgroundDimmerAlpha.coerceIn(0f, 0.85f),
                primaryAccentArgb = manifest.primaryAccentArgb,
                customBackgroundPath = bgPath,
                customBackgroundLuminance = bgLum ?: manifest.customBackgroundLuminance.coerceIn(0f, 1f),
                customBackgroundVersion = if (bgPath != null) version else 0L,
                cardTexturePath = cardTexPath,
                cardTextureMode = parsedCardMode,
                cardTextureAlpha = manifest.cardTextureAlpha.coerceIn(0f, 1f),
                cardTextureVersion = if (cardTexPath != null) version else 0L,
                heroButtonTexturePath = heroTexPath,
                heroButtonTextureAlpha = manifest.heroButtonTextureAlpha.coerceIn(0f, 1f),
                heroButtonTextureVersion = if (heroTexPath != null) version else 0L,
                actionButtonTexturePath = actionTexPath,
                actionButtonTextureAlpha = manifest.actionButtonTextureAlpha.coerceIn(0f, 1f),
                actionButtonTextureVersion = if (actionTexPath != null) version else 0L,
                navBarTexturePath = navTexPath,
                navBarTextureAlpha = manifest.navBarTextureAlpha.coerceIn(0f, 1f),
                navBarTextureVersion = if (navTexPath != null) version else 0L,
                titleFontPath = titleFontPath,
                titleFontScale = manifest.titleFontScale.coerceIn(0.80f, 1.35f),
                titleFontVersion = if (titleFontPath != null) version else 0L,
                bodyFontPath = bodyFontPath,
                bodyFontScale = manifest.bodyFontScale.coerceIn(0.80f, 1.35f),
                bodyFontVersion = if (bodyFontPath != null) version else 0L,
                terminalFontPath = terminalFontPath,
                terminalFontScale = manifest.terminalFontScale.coerceIn(0.80f, 1.35f),
                terminalFontVersion = if (terminalFontPath != null) version else 0L
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File, maxBytes: Long) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        dest.delete()
                        error("El archivo excede el tamaño máximo permitido (${maxBytes / (1024 * 1024)} MB).")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("No se pudo abrir el archivo seleccionado.")
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            error("El archivo seleccionado está vacío.")
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun sanitizeFilename(raw: String, defaultName: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return base.ifBlank { defaultName }
    }

    private fun deleteSlotFiles(dir: File, prefix: String, exclude: File? = null) {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix) && f.absolutePath != exclude?.absolutePath) {
                f.delete()
            }
        }
    }
}
