package com.seenot.app.account

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenotSyncV2Test {
    private val store = InMemorySyncStore()

    @Test
    fun freshInstallPullOnlyDoesNotCreatePendingOps() {
        val result = SeenotSyncEngine(store).applyRemoteChanges(
            listOf(
                SyncChange(
                    serverSequence = 1,
                    changeId = "change-1",
                    entityType = SyncEntityType.MONITORED_APP.value,
                    entityId = "com.video",
                    operation = SyncOperation.UPSERT,
                    revision = 1,
                    payload = mapOf("package_name" to "com.video"),
                    deviceId = "device-a",
                    createdAt = "2026-05-25T00:00:00Z"
                )
            )
        )

        assertEquals(1, result.downloaded)
        assertEquals(1, store.lastServerSequence)
        assertTrue(store.pendingOps().isEmpty())
        assertEquals(1, store.getEntityState(SyncEntityType.MONITORED_APP.value, "com.video")?.revision)
    }

    @Test
    fun localDeleteQueuesTombstoneOpWithBaseRevision() {
        store.upsertEntityState(
            SyncEntityState(
                entityType = SyncEntityType.MONITORED_APP.value,
                entityId = "com.video",
                revision = 4,
                payload = mapOf("package_name" to "com.video"),
                updatedAt = "2026-05-25T00:00:00Z",
                updatedByDeviceId = "device-a"
            )
        )

        val op = SeenotSyncEngine(store).enqueueLocalDelete(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = "com.video"
        )

        assertEquals(SyncOperation.DELETE, op.operation)
        assertEquals(4, op.baseRevision)
        assertNull(op.payload)
        assertEquals(op, store.pendingOps().single())
    }

    @Test
    fun acceptedOpUpdatesBaselineAndIsRemovedFromQueue() {
        val op = store.enqueueOp(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = "com.chat",
            operation = SyncOperation.UPSERT,
            baseRevision = 0,
            payload = mapOf("package_name" to "com.chat")
        )

        val result = SeenotSyncEngine(store).applyPushResults(
            listOf(
                SyncOpResult(
                    opId = op.opId,
                    status = SyncOpStatus.APPLIED,
                    entityType = op.entityType,
                    entityId = op.entityId,
                    serverSequence = 7,
                    revision = 1,
                    entity = SyncEntityState(
                        entityType = op.entityType,
                        entityId = op.entityId,
                        revision = 1,
                        payload = mapOf("package_name" to "com.chat"),
                        updatedAt = "2026-05-25T00:00:00Z",
                        updatedByDeviceId = "device-a"
                    )
                )
            )
        )

        assertEquals(1, result.uploaded)
        assertTrue(store.pendingOps().isEmpty())
        assertEquals(7, store.lastServerSequence)
        assertEquals(1, store.getEntityState(op.entityType, op.entityId)?.revision)
    }

    @Test
    fun staleRemoteResultIsAppliedAndRemovedFromQueue() {
        val op = store.enqueueOp(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = "com.video",
            operation = SyncOperation.UPSERT,
            baseRevision = 2,
            payload = mapOf("package_name" to "com.video")
        )

        val result = SeenotSyncEngine(store).applyPushResults(
            listOf(
                SyncOpResult(
                    opId = op.opId,
                    status = SyncOpStatus.APPLIED,
                    entityType = op.entityType,
                    entityId = op.entityId,
                    serverSequence = 9,
                    revision = 3,
                    entity = SyncEntityState(
                        entityType = op.entityType,
                        entityId = op.entityId,
                        revision = 3,
                        payload = mapOf("package_name" to "com.video"),
                        updatedAt = "2026-05-25T00:00:00Z",
                        updatedByDeviceId = "device-a"
                    )
                )
            )
        )

        assertEquals(1, result.uploaded)
        assertTrue(store.pendingOps().isEmpty())
        assertEquals(3, store.getEntityState(op.entityType, op.entityId)?.revision)
    }

    @Test
    fun gsonPayloadUsesBackendFieldNames() {
        val json = Gson().toJson(
            SyncOpsRequest(
                ops = listOf(
                    SyncOpRequest(
                        opId = "op-1",
                        entityType = "monitored_app",
                        entityId = "com.video",
                        operation = SyncOperation.DELETE,
                        baseRevision = 3
                    )
                )
            )
        )

        assertTrue(json.contains("\"op_id\""))
        assertTrue(json.contains("\"entity_type\""))
        assertTrue(json.contains("\"base_revision\""))
        assertFalse(json.contains("profile_version"))
    }
}
