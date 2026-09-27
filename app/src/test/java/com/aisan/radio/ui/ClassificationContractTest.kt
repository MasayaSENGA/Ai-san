package com.aisan.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// files/scripts/stationdata.py(DB を作る側)とアプリの分類の値がずれていないかを確かめる
class ClassificationContractTest {

    private val source: String by lazy {
        // Gradle の単体テストは app/ を作業ディレクトリにして動く
        listOf(File("../files/scripts/stationdata.py"), File("files/scripts/stationdata.py"))
            .first { it.exists() }
            .readText()
    }

    private fun pythonStrings(name: String): List<String> {
        val body = Regex("""(?s)^$name = [\[(](.*?)[\])]""", RegexOption.MULTILINE).find(source)
            ?: error("$name が stationdata.py に無い")
        return Regex("\"([^\"]+)\"").findAll(body.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun prefecturesMatch() {
        assertEquals(pythonStrings("PREFECTURES"), PREFECTURES)
    }

    @Test
    fun categoriesMatch() {
        assertEquals(pythonStrings("CATEGORIES").toSet(), StationCategory.entries.map { it.dbValue }.toSet())
    }

    @Test
    fun rolesMatch() {
        assertEquals(pythonStrings("ROLES").toSet(), StationRole.entries.map { it.dbValue }.toSet())
    }
}
