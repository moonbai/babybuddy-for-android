package eu.pkgsoftware.babybuddywidgets

import android.os.Handler
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerAlternativeBinding
import eu.pkgsoftware.babybuddywidgets.history.ChildEventHistoryLoader
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.ChildrenStateTracker
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.timers.FragmentCallbacks
import eu.pkgsoftware.babybuddywidgets.timers.TimerControlInterface
import eu.pkgsoftware.babybuddywidgets.timers.TimersUpdatedCallback
import eu.pkgsoftware.babybuddywidgets.timers.TranslatedException
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromise
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromiseFailure
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import kotlinx.coroutines.launch
import java.util.Date

class AlternativeBabyLayoutHolderKotlin(
    private val fragment: BaseFragment,
    private val binding: BabyManagerAlternativeBinding
) : RecyclerView.ViewHolder(binding.root), TimerControlInterface {

    private var child: BabyBuddyClient.Child? = null
    private var childHistoryLoader: ChildEventHistoryLoader? = null
    private var childObserver: ChildrenStateTracker.ChildObserver? = null
    private var cachedTimers: Array<BabyBuddyClient.Timer>? = null
    private val updateTimersCallbacks = mutableListOf<TimersUpdatedCallback>()
    private var pendingTimerModificationCalls = 0

    private val timerHandler = Handler(fragment.mainActivity.mainLooper)
    private var activeTimerRunnable: Runnable? = null

    init {
        binding.mainScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY && childHistoryLoader != null) {
                childHistoryLoader?.updateTop()
            }
        }

        binding.feedingCard.setOnClickListener { startTimer(BabyBuddyClient.ACTIVITIES.FEEDING) }
        binding.sleepCard.setOnClickListener { startTimer(BabyBuddyClient.ACTIVITIES.SLEEP) }
        binding.diaperCard.setOnClickListener { showDiaperEditor() }
        binding.pumpingCard.setOnClickListener { startTimer(BabyBuddyClient.ACTIVITIES.PUMPING) }
        binding.tummyTimeCard.setOnClickListener { startTimer(BabyBuddyClient.ACTIVITIES.TUMMY_TIME) }
        binding.notesCard.setOnClickListener { showNoteEditor() }

        binding.stopTimerButton.setOnClickListener { stopActiveTimer() }
    }

    fun updateChild(c: BabyBuddyClient.Child?, stateTracker: ChildrenStateTracker?) {
        if (childObserver != null && child == c && stateTracker == childObserver?.getTracker()) {
            return
        }

        clear()
        child = c

        if (child != null && stateTracker != null) {
            childObserver = stateTracker.new ChildObserver(child!!.id) { timers ->
                updateTimerList(timers)
            }

            childHistoryLoader = ChildEventHistoryLoader(
                fragment,
                binding.innerTimeline,
                child!!.id,
                VisibilityCheck(binding.mainScrollView),
                binding.timelineProgressSpinner
            ) { entryType, _ ->
                val tActivity = fragment.translateActivityName(entryType)
                val msg = Phrase.from(fragment.resources, R.string.history_loading_timeline_entry_failed)
                    .put("activity", tActivity)
                    .format()
                    .toString()
                fragment.mainActivity.binding.globalErrorBubble.flashMessage(msg)
            }

            updateHeaderCard()
        }
    }

    private fun updateHeaderCard() {
        child?.let { c ->
            binding.childName.text = c.first_name
            binding.childAge.text = calculateAge(c.birth_date)
            updateTodayStats()
        }
    }

    private fun calculateAge(birthDate: Date): String {
        val now = Date()
        val diffMs = now.time - birthDate.time
        val days = diffMs / (1000 * 60 * 60 * 24)
        val months = days / 30
        val years = months / 12

        return when {
            years > 0 -> "$years year${if (years > 1) "s" else ""}, ${months % 12} month${if (months % 12 != 1L) "s" else ""} old"
            months > 0 -> "$months month${if (months > 1) "s" else ""} old"
            else -> "$days day${if (days > 1) "s" else ""} old"
        }
    }

    private fun updateTodayStats() {
        child?.let { c ->
            val client = fragment.mainActivity.client
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date())

            client.listFeedings(c.id, object : BabyBuddyClient.RequestCallback<Array<BabyBuddyClient.Timer>> {
                override fun error(error: Exception) {}
                override fun response(response: Array<BabyBuddyClient.Timer>?) {
                    val todayFeedings = response?.filter {
                        it.start.toString().startsWith(today)
                    }?.size ?: 0
                    binding.todayFeedings.post { binding.todayFeedings.text = todayFeedings.toString() }
                }
            })
        }
    }

    private fun startTimer(activityName: String) {
        child?.let { c ->
            fragment.mainActivity.scope.launch {
                try {
                    val timer = AsyncPromise.call<BabyBuddyClient.Timer, TranslatedException> { promise ->
                        createNewTimer(
                            BabyBuddyClient.Timer(
                                id = 0,
                                child_id = c.id,
                                name = activityName,
                                start = Date(),
                                active = true
                            ),
                            promise
                        )
                    }
                    showTimerCard(activityName)
                } catch (e: AsyncPromiseFailure) {
                    fragment.showError(true, R.string.activity_store_failure_message, e.value.toString())
                }
            }
        }
    }

    private fun showDiaperEditor() {
        fragment.showQuestion(
            true,
            fragment.getString(R.string.diaper_title),
            "Quick diaper log",
            "Wet only",
            "Wet + Solid",
            object : DialogCallback {
                override fun call(saveWet: Boolean) {
                    child?.let { c ->
                        fragment.mainActivity.scope.launch {
                            try {
                                val entry = fragment.mainActivity.client.v2client.createEntry(
                                    eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry::class,
                                    eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry(
                                        id = 0,
                                        childId = c.id,
                                        start = Date(),
                                        _notes = "",
                                        wet = true,
                                        solid = saveWet,
                                        color = "",
                                        amount = null
                                    )
                                )
                                childHistoryLoader?.forceRefresh()
                            } catch (e: Exception) {
                                fragment.showError(true, R.string.activity_store_failure_message, e.message ?: "Error")
                            }
                        }
                    }
                }
            }
        )
    }

    private fun showNoteEditor() {
        child?.let { c ->
            fragment.mainActivity.scope.launch {
                try {
                    val entry = fragment.mainActivity.client.v2client.createEntry(
                        eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry::class,
                        eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry(
                            id = 0,
                            childId = c.id,
                            start = Date(),
                            _notes = ""
                        )
                    )
                    childHistoryLoader?.forceRefresh()
                } catch (e: Exception) {
                    fragment.showError(true, R.string.activity_store_failure_message, e.message ?: "Error")
                }
            }
        }
    }

    private fun showTimerCard(activityName: String) {
        binding.timerCard.isVisible = true
        binding.timerActivity.text = fragment.translateActivityName(activityName)
        updateTimerDisplay()

        activeTimerRunnable = object : Runnable {
            override fun run() {
                updateTimerDisplay()
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(activeTimerRunnable!!)
    }

    private fun updateTimerDisplay() {
        cachedTimers?.firstOrNull { it.active }?.let { timer ->
            val diff = Date().time - timer.start.time
            val seconds = (diff / 1000).toInt()
            val minutes = seconds / 60
            val hours = minutes / 60
            val timeStr = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            binding.timerDuration.text = timeStr
        }
    }

    private fun stopActiveTimer() {
        cachedTimers?.firstOrNull { it.active }?.let { timer ->
            fragment.mainActivity.scope.launch {
                try {
                    AsyncPromise.call<Any, TranslatedException> { promise ->
                        stopTimer(timer, promise)
                    }
                    binding.timerCard.isVisible = false
                    activeTimerRunnable?.let { timerHandler.removeCallbacks(it) }
                    childHistoryLoader?.forceRefresh()
                } catch (e: AsyncPromiseFailure) {
                    fragment.showError(true, R.string.activity_store_failure_message, e.value.toString())
                }
            }
        }
    }

    fun updateTimerList(timers: Array<BabyBuddyClient.Timer>) {
        if (pendingTimerModificationCalls > 0) return

        child?.let { c ->
            val filteredTimers = timers.filter { it.child_id == c.id }
            if (filteredTimers.isEmpty() && cachedTimers?.isEmpty() == true) return

            cachedTimers = filteredTimers.toTypedArray()

            val activeTimer = filteredTimers.firstOrNull { it.active }
            if (activeTimer != null) {
                if (!binding.timerCard.isVisible) {
                    showTimerCard(activeTimer.name)
                }
            } else {
                binding.timerCard.isVisible = false
                activeTimerRunnable?.let { timerHandler.removeCallbacks(it) }
            }

            callTimerUpdateCallback()
        }
    }

    fun onViewDeselected() {
        childObserver?.close()
        childHistoryLoader?.close()
    }

    fun clear() {
        activeTimerRunnable?.let { timerHandler.removeCallbacks(it) }
        childObserver?.close()
        childObserver = null
        childHistoryLoader?.close()
        childHistoryLoader = null
        child = null
        cachedTimers = null
        binding.timerCard.isVisible = false
    }

    fun close() {
        clear()
    }

    private fun callTimerUpdateCallback() {
        child?.let { c ->
            val wrapped = fragment.mainActivity.getChildTimerControl(c)
            cachedTimers?.let {
                (wrapped.wrap as? TimersUpdatedCallback)?.newTimerListLoaded(it)
            }
        }
    }

    override fun createNewTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { c ->
            fragment.mainActivity.getChildTimerControl(c).createNewTimer(timer, UpdateBufferingPromise(cb))
        }
    }

    override fun startTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { c ->
            fragment.mainActivity.getChildTimerControl(c).startTimer(timer, UpdateBufferingPromise(cb))
        }
    }

    override fun stopTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<Any, TranslatedException>
    ) {
        child?.let { c ->
            fragment.mainActivity.getChildTimerControl(c).stopTimer(timer, UpdateBufferingPromise(cb))
        }
    }

    override fun registerTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        if (!updateTimersCallbacks.contains(callback)) {
            updateTimersCallbacks.add(callback)
            child?.let { c ->
                fragment.mainActivity.getChildTimerControl(c).registerTimersUpdatedCallback { timers ->
                    updateTimersCallbacks.forEach { it.newTimerListLoaded(timers) }
                }
            }
            callTimerUpdateCallback()
        }
    }

    override fun unregisterTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        updateTimersCallbacks.remove(callback)
    }

    override fun getNotes(timer: BabyBuddyClient.Timer): CredStore.Notes {
        return child?.let {
            fragment.mainActivity.getChildTimerControl(it).getNotes(timer)
        } ?: CredStore.Notes("", Date())
    }

    override fun setNotes(timer: BabyBuddyClient.Timer, notes: CredStore.Notes) {
        child?.let {
            fragment.mainActivity.getChildTimerControl(it).setNotes(timer, notes)
        }
    }

    private inner class UpdateBufferingPromise<A, B>(private val promise: Promise<A, B>) : Promise<A, B> {
        init {
            pendingTimerModificationCalls++
        }

        override fun succeeded(a: A) {
            pendingTimerModificationCalls--
            promise.succeeded(a)
        }

        override fun failed(b: B) {
            pendingTimerModificationCalls--
            promise.failed(b)
        }
    }
}
