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
            PermissionPlan.nextRequest(grants())
        )
    }

    @Test
    fun `asks for background location only after foreground is granted`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            PermissionPlan.nextRequest(grants(fine = true))
        )
    }

    @Test
    fun `never bundles background with foreground`() {
        val request = PermissionPlan.nextRequest(grants())!!
        assert(!request.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            "a combined foreground+background request is silently denied from API 30"
        }
    }

    @Test
    fun `skips background when the config does not want Always`() {
        assertEquals(
            listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            PermissionPlan.nextRequest(grants(fine = true, wantsAlways = false))
        )
    }

    @Test
    fun `still asks for activity recognition after location is fully granted`() {
        // The whole point of the RN bridge's late early-return: without AR the
        // motion machine needs ~200 m to re-engage.
        assertEquals(
            listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            PermissionPlan.nextRequest(grants(fine = true, background = true))
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
    fun `a full escalation terminates`() {
        // Drive the plan the way PermissionRequester does; it must not loop.
        var g = grants()
        var steps = 0
        while (true) {
            val next = PermissionPlan.nextRequest(g) ?: break
            steps++
            if (steps > 5) error("escalation did not terminate: $next")
            g = when {
                next.contains(Manifest.permission.ACCESS_FINE_LOCATION) -> g.copy(hasFineOrCoarse = true)
                next.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> g.copy(hasBackground = true)
                else -> g.copy(hasActivityRecognition = true)
            }
        }
        assertEquals(3, steps)
    }
}
