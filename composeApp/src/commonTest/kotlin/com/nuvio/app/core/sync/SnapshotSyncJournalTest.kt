package com.nuvio.app.core.sync

import kotlinx.serialization.json.*
import kotlin.test.*

class SnapshotSyncJournalTest {
    private fun array(value: String) = Json.parseToJsonElement(value).jsonArray

    @Test fun remoteAdditionsAndLocalEditsSurviveTheSameRefresh() {
        val base = array("""[{"id":"a","title":"Antes"}]""")
        val local = array("""[{"id":"a","title":"Meu título"},{"id":"b","title":"Local"}]""")
        val remote = array("""[{"id":"a","title":"Antes","future":true},{"id":"c","title":"Nuvio"}]""")
        assertEquals(array("""[{"id":"a","title":"Meu título","future":true},{"id":"b","title":"Local"},{"id":"c","title":"Nuvio"}]"""),
            mergeSyncSnapshots(base, local, remote))
    }

    @Test fun confirmedLocalAndRemoteRemovalsDoNotResurrectItems() {
        val base = array("""[{"id":"a"},{"id":"b"}]""")
        assertEquals(array("[]"), mergeSyncSnapshots(base, array("""[{"id":"b"}]"""), array("""[{"id":"a"}]""")))
        assertEquals(array("[]"), mergeSyncSnapshots(base, base, array("[]")))
    }

    @Test fun nestedFolderEditsAndUnknownFieldsAreMergedIndependently() {
        val base = array("""[{"id":"a","folders":[{"id":"f","title":"Antigo","order":1}]}]""")
        val local = array("""[{"id":"a","folders":[{"id":"f","title":"Novo","order":1}]}]""")
        val remote = array("""[{"id":"a","future":{"edition":"BR"},"folders":[{"id":"f","title":"Antigo","order":2},{"id":"g","title":"Remoto"}]}]""")
        val result = mergeSyncSnapshots(base, local, remote).single().jsonObject
        assertEquals("BR", result["future"]!!.jsonObject["edition"]!!.jsonPrimitive.content)
        assertEquals("Novo", result["folders"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(2, result["folders"]!!.jsonArray[0].jsonObject["order"]!!.jsonPrimitive.int)
        assertEquals(2, result["folders"]!!.jsonArray.size)
    }

    @Test fun deletingAnItemDoesNotUndoRemoteOrderingOfTheSurvivors() {
        val base = array("""[{"id":"a"},{"id":"b"},{"id":"c"}]""")
        val local = array("""[{"id":"a"},{"id":"c"}]""")
        val remote = array("""[{"id":"c"},{"id":"b"},{"id":"a"}]""")
        assertEquals(array("""[{"id":"c"},{"id":"a"}]"""), mergeSyncSnapshots(base, local, remote))
    }

    @Test fun addonTransportIdentitiesRetainDistinctQueryConfiguration() {
        val local = array("""[{"url":"https://fixture.invalid/manifest.json?config=a","enabled":false}]""")
        val remote = array("""[{"url":"https://fixture.invalid/manifest.json?config=b","enabled":true}]""")
        assertEquals(2, mergeSyncSnapshots(array("[]"), local, remote, "url").size)
    }

    @Test fun journalSurvivesRecreationAndOnlyAcknowledgesTheUploadedRevision() {
        var disk: String? = null
        fun journal() = SnapshotSyncJournal({ disk }, { disk = it })
        val empty = array("[]")
        val first = array("""[{"id":"a","title":"Primeiro"}]""")
        journal().recordLocal(empty, first)
        val uploading = journal().pending()!!
        val edited = array("""[{"id":"a","title":"Último"}]""")
        journal().recordLocal(first, edited)
        journal().acknowledge(uploading.first, uploading.second)
        assertEquals(edited, journal().pending()!!.second)
        val current = journal().pending()!!
        journal().acknowledge(current.first, current.second)
        assertNull(journal().pending())
        assertEquals(edited, journal().plan(edited).merged)
    }

    @Test fun stalePullCannotOverwriteAnEditThatArrivedDuringNetworkIO() {
        var disk: String? = null
        val journal = SnapshotSyncJournal({ disk }, { disk = it })
        val empty = array("[]")
        val first = array("""[{"id":"a","title":"Primeiro"}]""")
        journal.recordLocal(empty, first)
        val planned = journal.plan(empty)
        val edited = array("""[{"id":"a","title":"Novo"}]""")
        journal.recordLocal(first, edited)
        assertFalse(journal.commitPull(planned))
        assertEquals(edited, journal.pending()!!.second)
    }

    @Test fun failedPublicationAndFutureSchemaKeepThePreviousJournal() {
        var disk: String? = null
        var fail = false
        val journal = SnapshotSyncJournal({ disk }, { if (fail) error("disk unavailable") else disk = it })
        journal.recordLocal(array("[]"), array("""[{"id":"a"}]"""))
        val previous = disk
        fail = true
        assertFails { journal.recordLocal(array("[]"), array("""[{"id":"b"}]""")) }
        assertEquals(previous, disk)
        fail = false
        disk = """{"schema":99,"local":[],"baseline":[]}"""
        val future = disk
        assertFails { journal.plan(array("[]")) }
        assertFails { journal.recordLocal(array("[]"), array("[]")) }
        assertEquals(future, disk)
    }

    @Test fun duplicateMissingAndOversizedIdentitiesAreRejectedWithoutWriting() {
        var writes = 0
        val journal = SnapshotSyncJournal({ null }, { writes++ })
        assertFails { journal.plan(array("""[{"id":"a"},{"id":"a"}]""")) }
        assertFails { journal.plan(array("""[{"title":"Missing"}]""")) }
        assertFails { journal.plan(JsonArray(listOf(buildJsonObject { put("id", "a".repeat(16385)) }))) }
        assertEquals(0, writes)
    }
}
