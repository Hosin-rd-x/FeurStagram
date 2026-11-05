package com.feurstagram.patches.ios

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import java.io.File
import java.util.logging.Logger

/**
 * iPhone look for Instagram's own screens (RdxGram fork).
 *
 * 1. Font. Android Instagram draws its UI in "IG UI Beta" (res/font/IGUIBetav9_*)
 *    and its headers in "Optimistic Display"; the iPhone app uses Apple's system
 *    font. Apple's font can't be redistributed, so the files are swapped for
 *    Inter (SIL OFL), the closest free match, weight for weight.
 *
 * 2. Icons. Android shows an arrow for "back" and a vertical "⋮" for "more"; iOS
 *    shows a chevron "‹" and a horizontal "…". Instagram's icons are
 *    AppIkonDrawable XML files that only point into a shared glyph table by
 *    `vector_index`, so each Android icon is pointed at its iOS counterpart's
 *    glyph. Right-to-left variants (drawable-ldrtl, used by Persian) get the
 *    mirrored chevron.
 *
 * Everything is matched by file name with a content fallback for the fonts, and
 * the patch fails loudly when it changes nothing, so a renamed resource shows up
 * in the build report instead of shipping an unchanged app.
 */
private val log = Logger.getLogger("RdxIosLook")

private const val FONTS = "rdx/fonts"

private fun bundled(name: String): ByteArray =
    object {}.javaClass.classLoader.getResourceAsStream("$FONTS/$name")?.use { it.readBytes() }
        ?: throw PatchException("$FONTS/$name is missing from the patch bundle")

/** Font file stem (or family name found inside the file) -> Inter replacement. */
private fun fontReplacement(file: File): String? {
    val stem = file.nameWithoutExtension.lowercase()
    when {
        stem.startsWith("iguibeta") && stem.endsWith("_bold") -> return "Inter-Bold.ttf"
        stem.startsWith("iguibeta") && stem.endsWith("_semibold") -> return "Inter-SemiBold.ttf"
        stem.startsWith("iguibeta") && stem.endsWith("_regular") -> return "Inter-Regular.ttf"
        stem == "optimistic_display_a_bd" -> return "Inter-Bold.ttf"
        stem == "optimistic_display_a_md" -> return "Inter-SemiBold.ttf"
    }
    // Obfuscated file names: look for the family name in the font's name table.
    val bytes = file.readBytes()
    fun has(text: String) = indexOf(bytes, utf16be(text)) >= 0 || indexOf(bytes, text.toByteArray()) >= 0
    return when {
        has("IG UI Beta") && has("SemiBold") -> "Inter-SemiBold.ttf"
        has("IG UI Beta") && has("Bold") -> "Inter-Bold.ttf"
        has("IG UI Beta") -> "Inter-Regular.ttf"
        has("Optimistic Display App Medium") -> "Inter-SemiBold.ttf"
        has("Optimistic Display App") -> "Inter-Bold.ttf"
        else -> null
    }
}

private fun utf16be(text: String) = text.toByteArray(Charsets.UTF_16BE)

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private val VECTOR_INDEX = Regex("""vector_index="(\d+)"""")

/** Target icon -> its iOS counterpart (for left-to-right screens). */
private fun iosIconFor(name: String, rtl: Boolean): String? {
    val arrow = Regex("^instagram_arrow_left_(.+)$").find(name)
    if (arrow != null) {
        val side = if (rtl) "right" else "left"
        return "instagram_chevron_${side}_${arrow.groupValues[1]}"
    }
    val more = Regex("^instagram_more_vertical_(.+)$").find(name)
    if (more != null) return "instagram_more_horizontal_${more.groupValues[1]}"
    return null
}

@Suppress("unused")
val iosLookPatch = resourcePatch(
    name = "iOS look",
    description = "Makes Instagram's own screens look like the iPhone app: an Apple-style " +
        "font (Inter) and iOS icons (chevron back, horizontal more).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val res = get("res")
        if (!res.isDirectory) throw PatchException("res/ was not decoded")

        // --- Fonts -------------------------------------------------------------
        var fonts = 0
        res.walkTopDown()
            .filter { it.isFile && (it.extension == "ttf" || it.extension == "otf") }
            .forEach { file ->
                val replacement = fontReplacement(file) ?: return@forEach
                file.writeBytes(bundled(replacement))
                log.info("font ${file.name} -> $replacement")
                fonts++
            }
        // Optimistic variable font used by newer surfaces, shipped as an asset.
        val optimisticVf = get("assets/Optimistic_VF_App_Lite.ttf")
        if (optimisticVf.isFile) {
            optimisticVf.writeBytes(bundled("InterVariable.ttf"))
            fonts++
        }

        // --- Icons -------------------------------------------------------------
        // The glyph index of each icon as defined for left-to-right screens.
        val baseIndex = HashMap<String, String>()
        File(res, "drawable").listFiles()?.forEach { file ->
            if (file.extension != "xml") return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            VECTOR_INDEX.find(text)?.let { baseIndex[file.nameWithoutExtension] = it.groupValues[1] }
        }

        var icons = 0
        res.listFiles()?.filter { it.isDirectory && it.name.startsWith("drawable") }?.forEach { dir ->
            val rtl = dir.name.contains("ldrtl")
            dir.listFiles()?.forEach { file ->
                if (file.extension != "xml") return@forEach
                val source = iosIconFor(file.nameWithoutExtension, rtl) ?: return@forEach
                val index = baseIndex[source] ?: return@forEach
                val text = file.readText()
                if (!VECTOR_INDEX.containsMatchIn(text)) return@forEach
                file.writeText(VECTOR_INDEX.replace(text, """vector_index="$index""""))
                icons++
            }
        }

        log.info("iOS look: $fonts font files, $icons icons replaced")
        if (fonts == 0 && icons == 0) {
            throw PatchException("iOS look changed nothing: Instagram's font or icon resources moved")
        }
    }
}
