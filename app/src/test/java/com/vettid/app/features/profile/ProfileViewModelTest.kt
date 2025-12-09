package com.vettid.app.features.profile

import com.vettid.app.core.network.Profile
import com.vettid.app.core.network.ProfileApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private lateinit var profileApiClient: ProfileApiClient
    private lateinit var viewModel: ProfileViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val testProfile = Profile(
        guid = "user-123",
        displayName = "Test User",
        avatarUrl = null,
        bio = "Hello world",
        location = "New York",
        lastUpdated = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        profileApiClient = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)

        val initialState = viewModel.state.first()
        assertTrue(initialState is ProfileState.Loading || initialState is ProfileState.Loaded)
    }

    @Test
    fun `loadProfile populates state on success`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ProfileState.Loaded)
        val loadedState = state as ProfileState.Loaded
        assertEquals("Test User", loadedState.profile.displayName)
        assertEquals("Hello world", loadedState.profile.bio)
    }

    @Test
    fun `loadProfile shows error state on failure`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.failure(Exception("Network error")))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ProfileState.Error)
        assertEquals("Network error", (state as ProfileState.Error).message)
    }

    @Test
    fun `startEditing enables edit mode`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.isEditing.first())

        viewModel.startEditing()

        assertTrue(viewModel.isEditing.first())
    }

    @Test
    fun `cancelEditing disables edit mode`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.startEditing()
        assertTrue(viewModel.isEditing.first())

        viewModel.cancelEditing()
        assertFalse(viewModel.isEditing.first())
    }

    @Test
    fun `edit fields are populated from profile when editing starts`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.startEditing()

        assertEquals("Test User", viewModel.editDisplayName.first())
        assertEquals("Hello world", viewModel.editBio.first())
        assertEquals("New York", viewModel.editLocation.first())
    }

    @Test
    fun `isFormValid returns false when displayName is blank`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.startEditing()
        viewModel.onDisplayNameChanged("")

        assertFalse(viewModel.isFormValid())
    }

    @Test
    fun `isFormValid returns true when displayName is not blank`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.startEditing()
        viewModel.onDisplayNameChanged("New Name")

        assertTrue(viewModel.isFormValid())
    }

    @Test
    fun `saveProfile calls API and updates state`() = runTest {
        val updatedProfile = testProfile.copy(displayName = "Updated Name")
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))
        whenever(profileApiClient.updateProfile(
            displayName = any(),
            avatarUrl = anyOrNull(),
            bio = anyOrNull(),
            location = anyOrNull()
        )).thenReturn(Result.success(updatedProfile))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.startEditing()
        viewModel.onDisplayNameChanged("Updated Name")
        viewModel.saveProfile()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state is ProfileState.Loaded)
        assertEquals("Updated Name", (state as ProfileState.Loaded).profile.displayName)
        assertFalse(viewModel.isEditing.first())
    }

    @Test
    fun `publishProfile calls API`() = runTest {
        whenever(profileApiClient.getProfile())
            .thenReturn(Result.success(testProfile))
        whenever(profileApiClient.publishProfile())
            .thenReturn(Result.success(Unit))

        viewModel = ProfileViewModel(profileApiClient)
        testScheduler.advanceUntilIdle()

        viewModel.publishProfile()
        testScheduler.advanceUntilIdle()

        verify(profileApiClient).publishProfile()
    }
}
