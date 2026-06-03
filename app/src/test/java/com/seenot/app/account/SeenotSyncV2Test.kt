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
    fun pagedPullUsesResponseCursorInsteadOfHighestChangeSequence() {
        val result = SeenotSyncEngine(store).applyRemoteChanges(
            changes = listOf(
                SyncChange(
                    serverSequence = 208,
                    changeId = "change-208",
                    entityType = SyncEntityType.GLOBAL_PREF.value,
                    entityId = "global",
                    operation = SyncOperation.UPSERT,
                    revision = 1,
                    payload = mapOf("show_home_timeline" to false),
                    deviceId = "device-a",
                    createdAt = "2026-05-25T00:00:00Z"
                )
            ),
            cursorSequence = 200
        )

        assertEquals(1, result.downloaded)
        assertEquals(200, store.lastServerSequence)
        assertEquals(1, store.getEntityState(SyncEntityType.GLOBAL_PREF.value, "global")?.revision)
    }

    @Test
    fun snapshotBootstrapUsesSnapshotSequenceAndDoesNotCreatePendingOps() {
        store.enqueueOp(
            entityType = SyncEntityType.GLOBAL_PREF.value,
            entityId = "global",
            operation = SyncOperation.UPSERT,
            baseRevision = 0,
            payload = mapOf("show_home_timeline" to true)
        )

        val result = SeenotSyncEngine(store).applyEntitySnapshot(
            SyncEntitySnapshotResponse(
                entities = listOf(
                    SyncEntityState(
                        entityType = SyncEntityType.GLOBAL_PREF.value,
                        entityId = "global",
                        revision = 3,
                        payload = mapOf("show_home_timeline" to false),
                        updatedAt = "2026-05-25T00:00:00Z",
                        updatedByDeviceId = "device-a"
                    )
                ),
                snapshotSequence = 42
            )
        )

        assertEquals(1, result.downloaded)
        assertEquals(42, store.lastServerSequence)
        assertTrue(store.pendingOps().isEmpty())
        assertEquals(3, store.getEntityState(SyncEntityType.GLOBAL_PREF.value, "global")?.revision)
    }

    @Test
    fun activeSnapshotClearsLocalTombstonesMissingFromFreshSnapshot() {
        store.upsertEntityState(
            SyncEntityState(
                entityType = SyncEntityType.MONITORED_APP.value,
                entityId = "com.old",
                revision = 2,
                payload = null,
                updatedAt = "2026-05-25T00:00:00Z",
                updatedByDeviceId = "device-a",
                deletedAt = "2026-05-25T00:01:00Z"
            )
        )

        SeenotSyncEngine(store).applyEntitySnapshot(
            SyncEntitySnapshotResponse(
                entities = listOf(
                    SyncEntityState(
                        entityType = SyncEntityType.MONITORED_APP.value,
                        entityId = "com.current",
                        revision = 1,
                        payload = mapOf("package_name" to "com.current"),
                        updatedAt = "2026-05-25T00:02:00Z",
                        updatedByDeviceId = "device-b"
                    )
                ),
                snapshotSequence = 10
            )
        )

        assertNull(store.getEntityState(SyncEntityType.MONITORED_APP.value, "com.old"))
        assertEquals(1, store.getEntityState(SyncEntityType.MONITORED_APP.value, "com.current")?.revision)
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
    fun conflictPushResultUpdatesBaselineAndRemovesPendingOp() {
        val op = store.enqueueOp(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = "com.video",
            operation = SyncOperation.UPSERT,
            baseRevision = 1,
            payload = mapOf("package_name" to "com.video")
        )

        val result = SeenotSyncEngine(store).applyPushResults(
            listOf(
                SyncOpResult(
                    opId = op.opId,
                    status = SyncOpStatus.CONFLICT,
                    entityType = op.entityType,
                    entityId = op.entityId,
                    revision = 2,
                    entity = SyncEntityState(
                        entityType = op.entityType,
                        entityId = op.entityId,
                        revision = 2,
                        payload = null,
                        updatedAt = "2026-05-25T00:00:00Z",
                        updatedByDeviceId = "device-a",
                        deletedAt = "2026-05-25T00:01:00Z"
                    ),
                    conflictReason = "tombstone_stale_base_revision"
                )
            )
        )

        assertEquals(0, result.uploaded)
        assertTrue(store.pendingOps().isEmpty())
        assertEquals(2, store.getEntityState(op.entityType, op.entityId)?.revision)
        assertEquals("2026-05-25T00:01:00Z", store.getEntityState(op.entityType, op.entityId)?.deletedAt)
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
