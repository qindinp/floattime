package com.floattime.app

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

class SettingsRepositoryTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        repository = SettingsRepository(mockContext)
    }

    @Test
    fun `timeSource default value`() {
        `when`(mockPrefs.getString(eq("time_source"), anyString())).thenReturn("local")
        assertEquals("local", repository.timeSource)
    }

    @Test
    fun `timeSource setter calls putString`() {
        repository.timeSource = "taobao"
        verify(mockEditor).putString("time_source", "taobao")
        verify(mockEditor).apply()
    }

    @Test
    fun `superIslandEnabled default is false`() {
        `when`(mockPrefs.getBoolean(eq("super_island_enabled"), anyBoolean())).thenReturn(false)
        assertFalse(repository.superIslandEnabled)
    }

    @Test
    fun `theme default is auto`() {
        `when`(mockPrefs.getString(eq("theme"), anyString())).thenReturn("auto")
        assertEquals("auto", repository.theme)
    }

    @Test
    fun `isServiceRunning persistence`() {
        repository.isServiceRunning = true
        verify(mockEditor).putBoolean("is_service_running", true)
        verify(mockEditor).apply()
    }
}
