package com.swastik.mbfitness.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swastik.mbfitness.data.MemberDraft
import com.swastik.mbfitness.data.MemberSaveResult
import com.swastik.mbfitness.domain.MessageKind
import com.swastik.mbfitness.domain.MessageStatus
import com.swastik.mbfitness.ui.navigation.AppNavigation
import com.swastik.mbfitness.ui.theme.MbTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek

/**
 * The real screens on an in-memory database, with a fake sender. Includes the SPEC 15 tests: a
 * quiet day blocks the run, and the broadcast pacing message. The clock says Friday 1 Jan 2027.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class MessagingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val today get() = app.fakeClock.today()

    @Before
    fun show() {
        runBlocking { app.settings.update { it.copy(quietWeekdays = emptySet(), trainerPhone = "+919000000099") } }
        compose.setContent { MbTheme { AppNavigation() } }
    }

    private fun waitForText(text: String) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()

    private fun waitGone(text: String) = compose.waitUntil(WAIT_MILLIS) { gone(text) }

    private fun tap(label: String) {
        waitForText(label)
        runCatching { compose.onNodeWithText(label).performScrollTo() }
        compose.onNodeWithText(label).performClick()
    }

    private fun type(fieldLabel: String, text: String) {
        waitForText(fieldLabel)
        compose.onNode(hasSetTextAction() and hasText(fieldLabel)).performTextReplacement(text)
    }

    private fun member(name: String, phone: String, consent: Boolean = true, expiry: Long = today): Long = runBlocking {
        (app.members.addMember(MemberDraft(name, phone, 1, expiry, smsConsent = consent)) as MemberSaveResult.Saved).memberId
    }

    private fun members(count: Int, consent: Boolean = true) = repeat(count) { index ->
        member("Member ${index + 1}", "90000${(index + 10000)}", consent, expiry = today + 30)
    }

    private fun queued() = runBlocking { app.database.messages().withStatusOnly(MessageStatus.QUEUED) }

    /** Asking for nodes is what lets the main thread run while this waits. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(WAIT_MILLIS) {
        compose.onAllNodesWithText("Anything").fetchSemanticsNodes()
        condition()
    }

    private fun openMessages() {
        compose.waitUntil(WAIT_MILLIS) {
            compose.onAllNodesWithContentDescription("Collected this month", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("SMS").performClick()
        waitForText("Run reminders now")
    }

    private fun openSettings(row: String) {
        compose.onNodeWithText("Settings").performClick()
        tap(row)
    }

    /** SPEC 15: "quiet day blocks run". */
    @Test
    fun aQuietDayAsksBeforeRunningAndNothingGoesIfYouWait() {
        runBlocking { app.settings.update { it.copy(quietWeekdays = setOf(DayOfWeek.FRIDAY)) } }
        member("Rahul Sharma", "9876543210")
        openMessages()

        tap("Run reminders now")
        waitForText("Today is a quiet day")
        waitForText("Friday")
        tap("Wait")
        waitGone("Today is a quiet day")
        assertTrue(queued().isEmpty())

        tap("Run reminders now")
        tap("Send anyway")
        waitForText("Send 1 reminder?")
        waitForText("Rahul Sharma · Due today")
        tap("Send")
        waitForText("1 reminder is on its way")
        waitFor { queued().size == 1 }
        assertEquals(MessageKind.ON_DUE, queued().single().kind)
    }

    @Test
    fun runningRemindersTwiceSendsNothingNew() {
        member("Rahul Sharma", "9876543210")
        openMessages()
        tap("Run reminders now")
        tap("Send")
        waitFor { queued().size == 1 }

        waitGone("Send 1 reminder?")
        tap("Run reminders now")
        waitForText("No reminders are due right now")
        assertEquals(1, queued().size)
    }

    @Test
    fun anUnfilledPlaceholderIsExplainedInsteadOfSent() {
        runBlocking { app.settings.update { it.copy(trainerPhone = "") } }
        member("Rahul Sharma", "9876543210", expiry = today - 7)
        openMessages()
        tap("Run reminders now")
        waitForText("add your phone number in Settings")
        assertTrue(queued().isEmpty())
    }

    /** SPEC 15: "broadcast pacing message", and SPEC 8.4's wording. */
    @Test
    fun aBroadcastSaysWhenItsMessagesWillGo() {
        members(30)
        openMessages()
        tap("Broadcast")
        waitForText("All active 30")
        compose.onNodeWithText("Send to 30 members").assertIsNotEnabled()

        type("Message", "Gym closed tomorrow.")
        waitForText("30 members × 1 part. 25 go now, 5 more at 10:30 AM.")
        waitForText("Gym closed tomorrow. - MB Fitness Club")

        compose.onNodeWithText("Send to 30 members").assertIsEnabled()
        tap("Send to 30 members")
        waitForText("Send to 30 members?")
        compose.onAllNodesWithText("Send")[0].performClick()

        waitFor { queued().size == 30 }
        assertTrue(queued().all { it.kind == MessageKind.BROADCAST && it.body == "Gym closed tomorrow. - MB Fitness Club" })
        waitFor { app.fakeKicker.kicks > 0 }
    }

    @Test
    fun aBroadcastRefusesLinksAndWontReachAnyoneWithoutConsent() {
        member("Agreed", "9876543210")
        member("Said no", "9876543211", consent = false)
        openMessages()
        tap("Broadcast")
        waitForText("All active 1")
        waitForText("1 person here hasn't agreed to SMS")

        type("Message", "See www.mbfitness.com for more")
        waitForText("Links aren't allowed")
        compose.onNodeWithText("Send to 1 member").assertIsNotEnabled()

        type("Message", "Closed on {aadhaar}")
        waitForText("{aadhaar} can't be used here")
        compose.onNodeWithText("Send to 1 member").assertIsNotEnabled()
    }

    @Test
    fun aBroadcastNotesAQuietDayButStillGoes() {
        runBlocking { app.settings.update { it.copy(quietWeekdays = setOf(DayOfWeek.FRIDAY)) } }
        member("Rahul Sharma", "9876543210")
        openMessages()
        tap("Broadcast")
        waitForText("Today is a quiet day (Friday). Broadcasts still go.")
    }

    @Test
    fun aBroadcastCanBeReusedAndCancelled() {
        members(3)
        openMessages()
        tap("Broadcast")
        type("Message", "Closed tomorrow.")
        tap("Send to 3 members")
        waitForText("Send to 3 members?")
        compose.onAllNodesWithText("Send")[0].performClick()
        waitFor { queued().size == 3 }

        waitForText("Broadcast · 3 people")
        tap("Cancel all")
        tap("Cancel them")
        waitFor { queued().isEmpty() }
        waitForText("Nothing waiting to send.")

        tap("Broadcast")
        waitForText("RECENT")
        tap("Closed tomorrow.")
        waitForText("Closed tomorrow. - MB Fitness Club")
    }

    @Test
    fun aFailedMessageCanBeRetriedOrCancelled() {
        val id = member("Rahul Sharma", "9876543210")
        val message = runBlocking {
            val row = app.messages.queueManual(id, "+919876543210", "Hi Rahul")
            val saved = app.database.messages().get(row)!!
            app.database.messages().update(saved.copy(status = MessageStatus.FAILED, lastError = "No signal", attempts = 3))
            row
        }
        openMessages()
        // A failure is what needs looking at, so that tab opens first.
        waitForText("No signal")
        waitForText("Failed 1")
        tap("Retry")
        waitForText("Trying again")
        waitFor { queued().singleOrNull()?.id == message }
        assertEquals(0, queued().single().attempts)
    }

    @Test
    fun noPermissionShowsABanner() {
        app.smsAllowed = false
        openMessages()
        waitForText("SMS permission is off")
    }

    @Test
    fun homeShowsThePermissionBannerAboveOthers() {
        app.smsAllowed = false
        member("Long Gone", "9876543210", expiry = today - 90)
        waitForText("SMS permission is off")
        assertTrue(gone("60+ days overdue"))
    }

    @Test
    fun homeShowsWhenRemindersLastRan() {
        waitForText("Reminders haven't run yet")
        runBlocking { app.dailyRunner.run() }
        waitForText("Reminders last ran: today 10:00 AM")
    }

    @Test
    fun aDayOrSoLateIsOnlyANote() {
        val hours = 60L * 60 * 1000
        runBlocking { app.settings.update { it.copy(lastDailyRunAt = app.fakeClock.nowMillis() - 30 * hours, lastDailyRunDay = today - 1) } }
        waitForText("Reminders last ran: yesterday")
        assertTrue(gone("Fix"))
    }

    @Test
    fun remindersStoppedGetsOneFixButton() {
        val hours = 60L * 60 * 1000
        runBlocking { app.settings.update { it.copy(lastDailyRunAt = app.fakeClock.nowMillis() - 72 * hours, lastDailyRunDay = today - 3) } }
        waitForText("Reminders haven't run for over 2 days")
        tap("Fix")
        waitForText("Reminders are set up again")
        waitForText("Reminders last ran: today")
        compose.waitUntil(WAIT_MILLIS) { gone("Reminders haven't run for over 2 days") }
        assertTrue(gone("Fix"))
    }

    /** The permission banner takes the slot, so Fix moves to the line itself. */
    @Test
    fun fixStaysReachableBehindAHigherPriorityBanner() {
        app.smsAllowed = false
        val hours = 60L * 60 * 1000
        runBlocking { app.settings.update { it.copy(lastDailyRunAt = app.fakeClock.nowMillis() - 72 * hours, lastDailyRunDay = today - 3) } }
        waitForText("SMS permission is off")
        assertTrue(gone("Reminders haven't run for over 2 days"))
        tap("Fix")
        waitForText("Reminders are set up again")
    }

    @Test
    fun aMemberWithoutConsentGetsAWarningBeforeASingleSms() {
        member("Rahul Sharma", "9876543210", consent = false)
        tap("Due soon")
        tap("Rahul Sharma")
        tap("Send SMS")
        waitForText("SMS to Rahul Sharma")
        type("Message", "Please call me.")
        waitForText("Please call me. - MB Fitness Club")
        compose.onAllNodesWithText("Send")[0].performClick()
        waitForText("hasn't agreed to get SMS")
        assertTrue(queued().isEmpty())
        tap("Send anyway")
        waitFor { queued().size == 1 }
        assertEquals("Please call me. - MB Fitness Club", queued().single().body)
        assertEquals(MessageKind.MANUAL, queued().single().kind)
    }

    @Test
    fun selectingMembersOnHomeStartsAHandPickedBroadcast() {
        member("Rahul Sharma", "9876543210")
        member("Anil Kumar", "9876543211")
        tap("Due soon")
        waitForText("Rahul Sharma")
        compose.onNodeWithText("Rahul Sharma").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Send SMS to selected").performClick()
        waitForText("Picked 1")
        type("Message", "Hello {name}")
        waitForText("Hello Rahul - MB Fitness Club")
    }

    @Test
    fun aLeftMemberIsOnlyReachedAfterAWarning() {
        val id = member("Rahul Sharma", "9876543210")
        runBlocking { app.members.markLeft(id) }
        tap("Frozen/Left")
        waitForText("Rahul Sharma")
        compose.onNodeWithText("Rahul Sharma").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Send SMS to selected").performClick()
        waitForText("Picked 1")
        type("Message", "Come back soon")
        tap("Send to 1 member")
        waitForText("Send to ex-members?")
        tap("Send anyway")
        waitForText("Send to 1 member?")
        compose.onAllNodesWithText("Send")[0].performClick()
        waitFor { queued().size == 1 }
    }

    @Test
    fun templatesCanBeEditedAndSaved() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        tap("Due today")
        waitForText("Sends: on the expiry day")
        waitForText("is due today")

        type("Wording", "Hi {name}, please pay {gym_name}.")
        waitForText("Hi Rahul, please pay MB Fitness Club.")
        tap("Save")
        waitForText("Saved")
        val saved = runBlocking { app.database.templates().get(MessageKind.ON_DUE)!! }
        assertEquals("Hi {name}, please pay {gym_name}.", saved.body)
    }

    @Test
    fun templatesRefuseLinksUnknownPlaceholdersAndOnesTheyCantFill() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        type("Wording", "See bit.ly/gym")
        waitForText("Links aren't allowed")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        type("Wording", "Your number is {aadhaar}")
        waitForText("{aadhaar} can't be used here")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        type("Wording", "Receipt {receipt_no}")
        waitForText("{receipt_no} can't be used here")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun curlyQuotesAreStraightened() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        type("Wording", "Don’t forget {name}")
        waitForText("Don't forget Rahul")
    }

    @Test
    fun aMessageOverTheLimitIsBlockedAndALongOneWarned() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        type("Wording", "x".repeat(400))
        waitForText("This costs 3 SMS per member")
        compose.onNodeWithText("Save").assertIsEnabled()
        type("Wording", "x".repeat(700))
        waitForText("Too long: 5 SMS parts")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun theScheduleCanBeChangedWithinLimits() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        type("Days before expiry", "5")
        waitForText("Sends: 5 days before expiry")
        tap("Save")
        waitForText("Saved")
        assertEquals(-5, runBlocking { app.database.templates().get(MessageKind.BEFORE_DUE)!!.offsetDays })

        type("Days before expiry", "99")
        waitForText("Must be 1 to 60 days before expiry")
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun switchingLanguageChangesWordingAndKeepsTheSchedule() {
        runBlocking {
            val row = app.database.templates().get(MessageKind.BEFORE_DUE)!!
            app.database.templates().upsert(row.copy(offsetDays = -5, enabled = false))
        }
        openSettings("Templates")
        waitForText("Sends: 5 days before expiry")
        tap("Hinglish")
        waitForText("Switch to Hinglish?")
        tap("Switch")
        waitForText("khatam ho rahi hai")
        val row = runBlocking { app.database.templates().get(MessageKind.BEFORE_DUE)!! }
        assertEquals(-5, row.offsetDays)
        assertEquals(false, row.enabled)
        assertEquals(com.swastik.mbfitness.domain.PackLanguage.HINGLISH, runBlocking { app.settings.settings.first().packLanguage })

        tap("Reset")
        waitForText("Reset to the Hinglish pack?")
        compose.onAllNodesWithText("Reset")[1].performClick()
        waitForText("Sends: 3 days before expiry")
        assertEquals(true, runBlocking { app.database.templates().get(MessageKind.BEFORE_DUE)!!.enabled })
    }

    @Test
    fun leavingWithUnsavedChangesAsksFirst() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        type("Wording", "Changed")
        compose.waitUntil(WAIT_MILLIS) { runCatching { compose.onNodeWithText("Save").assertIsEnabled() }.isSuccess }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Leave without saving?")
        tap("Keep editing")
        waitGone("Leave without saving?")
        waitForText("Changed")
    }

    @Test
    fun sendTestGoesToTheOwnerOnly() {
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        tap("Send test to me")
        waitForText("Test SMS is on its way")
        waitFor { queued().size == 1 }
        assertEquals("+919000000099", queued().single().phone)
        assertEquals(MessageKind.TEST, queued().single().kind)
    }

    @Test
    fun sendTestWithoutANumberExplainsWhy() {
        runBlocking { app.settings.update { it.copy(trainerPhone = "") } }
        openSettings("Templates")
        waitForText("Sends: 3 days before expiry")
        tap("Send test to me")
        waitForText("Add your phone number in Settings first")
        assertTrue(queued().isEmpty())
    }

    @Test
    fun quietDaysCanBeSetAndHolidaysAdded() {
        openSettings("Quiet days")
        waitForText("No automatic reminders go on quiet days")
        waitForText("EVERY WEEK")

        tap("Sun")
        waitFor { runBlocking { DayOfWeek.SUNDAY in app.settings.current().quietWeekdays } }
        waitForText("Next quiet day: Sun, 3 Jan (Sunday).")

        tap("Add holiday")
        waitForText("Pick a date")
        waitForText("Give it a name")
    }

    @Test
    fun aHolidayCanBeRemoved() {
        runBlocking { app.messages.addQuietDate(today + 2, "Diwali") }
        openSettings("Quiet days")
        waitForText("Diwali")
        waitForText("Next quiet day: Sun, 3 Jan (Diwali).")
        compose.onNodeWithContentDescription("Remove Diwali").performClick()
        waitGone("Diwali")
    }

    @Test
    fun everyDayQuietWarnsThatNothingWillGo() {
        runBlocking { app.settings.update { it.copy(quietWeekdays = DayOfWeek.entries.toSet()) } }
        openSettings("Quiet days")
        waitForText("Every day is quiet, so no reminders will ever go.")
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
