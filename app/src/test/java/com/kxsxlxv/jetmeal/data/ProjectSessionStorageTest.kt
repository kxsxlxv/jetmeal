package com.kxsxlxv.jetmeal.data

import org.junit.Assert.*
import org.junit.Test

class ProjectSessionStorageTest {
    @Test fun localAndCloudProjectsCannotReadEachOthersSessions() {
        val local = ProjectSessionStorage.namespace("http://127.0.0.1:54321")
        val cloud = ProjectSessionStorage.namespace("https://first-project.supabase.co")
        assertNotEquals(local, cloud)
        assertNotEquals(cloud, ProjectSessionStorage.namespace("https://second-project.supabase.co"))
        assertNotEquals(local, ProjectSessionStorage.namespace("http://127.0.0.1:54322"))
        assertNotEquals(cloud, "session")
    }

    @Test fun canonicalEndpointSurvivesUpdatesAndKeyRotation() {
        val namespace = ProjectSessionStorage.namespace("https://first-project.supabase.co")
        assertEquals(namespace, ProjectSessionStorage.namespace("https://FIRST-PROJECT.supabase.co/"))
        assertEquals(namespace, ProjectSessionStorage.namespace("https://first-project.supabase.co:443/"))
        assertEquals(namespace, ProjectSessionStorage.namespace("https://first-project.supabase.co"))
        assertTrue(namespace.matches(Regex("jetmeal_auth_[a-f0-9]{64}")))
    }

    @Test fun endpointsWithDifferentSchemesAndPathsAreIsolated() {
        assertNotEquals(ProjectSessionStorage.namespace("http://localhost:8000/a"), ProjectSessionStorage.namespace("http://localhost:8000/b"))
        assertNotEquals(ProjectSessionStorage.namespace("http://first-project.supabase.co"), ProjectSessionStorage.namespace("https://first-project.supabase.co"))
    }

    @Test fun credentialBearingUrlsAreRejectedWithoutEchoingThem() {
        listOf("https://user:secret@project.supabase.co", "https://project.supabase.co?token=secret", "https://project.supabase.co#secret").forEach {
            val error = runCatching { ProjectSessionStorage.namespace(it) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains("secret"))
        }
    }
}
