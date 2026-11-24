package com.blazikener.devin.data

import org.junit.Assert.*
import org.junit.Test

class DraftVersionTest {
    @Test fun editsForkAVersionWhileRouteMetadataKeepsIt() {
        val original = NewSessionDraft(prompt = "Task", draftVersion = "original")
        val metadata = original.copy(lastInitialPrompt = "Task").withVersionFrom(original)
        assertEquals(original.versionKey, metadata.versionKey)
        val edited = original.copy(prompt = "Task with context").withVersionFrom(original)
        assertNotEquals(original.versionKey, edited.versionKey)
        assertNotEquals(original.versionKey, edited.copy(prompt = "Task").withVersionFrom(edited).versionKey)
    }

    @Test fun legacyFormsHaveAStableVersionAcrossSerialization() {
        val legacy = NewSessionDraft(prompt = "Legacy task")
        val restored = json.decodeFromString(NewSessionDraft.serializer(), json.encodeToString(NewSessionDraft.serializer(), legacy))
        assertEquals(legacy.versionKey, restored.versionKey)
        assertEquals(legacy.versionKey, legacy.copy(lastInitialPrompt = "Legacy task").versionKey)
    }
}
