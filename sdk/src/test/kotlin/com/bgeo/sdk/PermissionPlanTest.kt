package com.bgeo.sdk

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionPlanTest {

    private fun grants(
        fine: Boolean = false, background: Boolean = false, activity: Boolean = false,
        wantsAlways: Boolean = true, sdkInt: Int = 34,
    ) = PermissionPlan.Grants(fine, background, activity, wantsAlways, sdkInt)

    @Test
    fun `asks for foreground location first`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionPlan.nextRequest(grants())?.permissions
        )
    }

    @Test
    fun `asks for background location only after foreground is granted`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            PermissionPlan.nextRequest(grants(fine = true))?.permissions
        )
    }

    @Test
    fun `never bundles background with foreground`() {
        val request = PermissionPlan.nextRequest(grants())!!.permissions
        assert(!request.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            "a combined foreground+background request is silently denied from API 30"
        }
    }

    @Test
    fun `skips background when the config does not want Always`() {
        assertEquals(
            listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            PermissionPlan.nextRequest(grants(fine = true, wantsAlways = false))?.permissions
        )
    }

    @Test
    fun `still asks for activity recognition after location is fully granted`() {
        // The whole point of the RN bridge's late early-return: without AR the
        // motion machine needs ~200 m to re-engage.
        assertEquals(
            listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            PermissionPlan.nextRequest(grants(fine = true, background = true))?.permissions
        )
    }

    @Test
    fun `is finished when everything is granted`() {
        assertNull(PermissionPlan.nextRequest(grants(fine = true, background = true, activity = true)))
    }

    @Test
    fun `does not request background location or activity recognition below API 29`() {
        // Neither permission exists before 29; requesting them is an error.
        assertNull(PermissionPlan.nextRequest(grants(fine = true, sdkInt = 28)))
    }

    @Test
    fun `a full escalation terminates when each stage is granted`() {
        // Drive the plan the way PermissionRequester does when everything is
        // eventually granted; it must not loop.
        var g = grants()
        var steps = 0
        while (true) {
            val next = PermissionPlan.nextRequest(g) ?: break
            steps++
            if (steps > 5) error("escalation did not terminate: $next")
            g = when (next.stage) {
                PermissionPlan.Stage.FOREGROUND -> g.copy(hasFineOrCoarse = true)
                PermissionPlan.Stage.BACKGROUND -> g.copy(hasBackground = true)
                PermissionPlan.Stage.ACTIVITY_RECOGNITION -> g.copy(hasActivityRecognition = true)
            }
        }
        assertEquals(3, steps)
    }

    // ---- attempted: the denial case a Grants-only decision cannot survive ----
    //
    // Grants alone cannot distinguish "never asked" from "asked and denied" -
    // without `attempted`, a permanently-denied stage would make `nextRequest`
    // return the same request forever. These prove `attempted` fixes that,
    // matching the bug the RN bridge's `finishPermission()` comment says it
    // fixed: resolving on BACKGROUND alone used to skip ACTIVITY_RECOGNITION.

    @Test
    fun `still reaches activity recognition after a denied foreground stage`() {
        assertEquals(
            PermissionPlan.Stage.ACTIVITY_RECOGNITION,
            PermissionPlan.nextRequest(grants(), attempted = setOf(PermissionPlan.Stage.FOREGROUND))?.stage
        )
    }

    @Test
    fun `background is never requested once attempted, even if still not granted`() {
        assertNull(
            PermissionPlan.nextRequest(
                grants(fine = true, activity = true),
                attempted = setOf(PermissionPlan.Stage.BACKGROUND),
            )
        )
    }

    @Test
    fun `escalation terminates even when nothing is ever granted`() {
        // The dangerous case a Grants-only `nextRequest` could not survive:
        // the OS permission state never changes (every stage denied), so only
        // `attempted` can make the loop progress toward termination.
        val fixed = grants()
        var attempted = emptySet<PermissionPlan.Stage>()
        val stagesInOrder = mutableListOf<PermissionPlan.Stage>()
        var steps = 0
        while (true) {
            val next = PermissionPlan.nextRequest(fixed, attempted) ?: break
            steps++
            if (steps > 5) error("escalation did not terminate: $next")
            stagesInOrder.add(next.stage)
            attempted = attempted + next.stage
        }
        // BACKGROUND is correctly never reached: it requires an actually-granted
        // foreground permission, which this fixed Grants never provides.
        assertEquals(listOf(PermissionPlan.Stage.FOREGROUND, PermissionPlan.Stage.ACTIVITY_RECOGNITION), stagesInOrder)
        assertEquals(2, steps)
    }
}
