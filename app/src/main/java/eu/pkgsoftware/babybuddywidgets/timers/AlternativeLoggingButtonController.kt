package eu.pkgsoftware.babybuddywidgets.timers;

import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import androidx.core.view.children;
import androidx.recyclerview.widget.RecyclerView;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.squareup.phrase.Phrase;
import eu.pkgsoftware.babybuddywidgets.BaseFragment;
import eu.pkgsoftware.babybuddywidgets.CredStore;
import eu.pkgsoftware.babybuddywidgets.DialogCallback;
import eu.pkgsoftware.babybuddywidgets.R;
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerAlternativeBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.DiaperLoggingEntryBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.FeedingLoggingEntryBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.GenericTimerLoggingEntryBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.NoteLoggingEntryBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.PumpingLoggingEntryBinding;
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.exponentialBackoff;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.maxDate;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.classActivityName;
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.nowServer;
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromise;
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromiseFailure;
import eu.pkgsoftware.babybuddywidgets.utils.ConcurrentEventBlocker;
import eu.pkgsoftware.babybuddywidgets.utils.Promise;
import eu.pkgsoftware.babybuddywidgets.widgets.SwitchButtonLogic;
import kotlinx.coroutines.Runnable;
import kotlinx.coroutines.launch;
import java.io.IOException;
import java.util.Date;
import kotlin.reflect.KClass;

@JsonIgnoreProperties(ignoreUnknown = true)
class AlternativeLoggingButtonControllerStoreState(
    @JsonProperty("open_state") val openState: Array<String>,
)

abstract class AlternativeLoggingControls(val childId: Int) {
    abstract val saveButton: ImageButton
    abstract val controlsView: View

    abstract fun storeStateForSuspend()
    abstract fun reset()
    abstract suspend fun save(): TimeEntry?

    open fun updateVisuals() {}
    open fun postInit() {}
    open fun postStart() {}
}

abstract class AlternativeGenericLoggingController(
    val fragment: BaseFragment,
    childId: Int,
    val timerControl: TimerControlInterface,
    val entryKlass: KClass<*>
) : AlternativeLoggingControls(childId), StoreFunction<TimeEntry> {
    protected abstract suspend fun createEntry(timer: BabyBuddyClient.Timer): TimeEntry

    val bindings = GenericTimerLoggingEntryBinding.inflate(fragment.layoutInflater)
    val typeName: String = classActivityName(entryKlass)

    open val uiIconList = bindings.icons.children
    open val uiNoteEditor = bindings.noteEditor
    open val uiCurrentTimerTime = bindings.currentTimerTime

    override val saveButton: ImageButton = bindings.sendButton
    override val controlsView: View = bindings.root

    private var timer: BabyBuddyClient.Timer? = null
    private var storingPromise: Promise<TimeEntry?, Exception>? = null

    override fun postInit() {
        fragment.mainActivity.storage.child<GenericTimerRecord>(childId, typeName)?.let {
            uiNoteEditor.setText(it.note)
        }

        val children = uiIconList.toList()
        for (i in BabyBuddyClient.ACTIVITIES.ALL.indices) {
            if (BabyBuddyClient.ACTIVITIES.ALL[i] == typeName) {
                children[i].visibility = View.VISIBLE
            } else {
                children[i].visibility = View.GONE
            }
        }

        updateVisuals()
    }

    override fun storeStateForSuspend() {
        fragment.mainActivity.storage.child(
            childId, typeName, GenericTimerRecord(uiNoteEditor.text.toString())
        )
    }

    override fun reset() {
        uiNoteEditor.setText("")
        storeStateForSuspend()
    }

    override suspend fun save(): TimeEntry? {
        storingPromise?.let {
            throw IOException("Already storing activity of type ${typeName}")
        }

        timer?.let { timer ->
            try {
                try {
                    val result = AsyncPromise.call<TimeEntry?, Exception> { promise ->
                        storingPromise = promise
                        fragment.mainActivity.storeActivity(timer, this)
                    }
                    return result
                }
                finally {
                    storingPromise = null
                }
            }
            catch (e: AsyncPromiseFailure) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    R.string.activity_store_failure_server_error
                )
            }
        }
        throw IOException("Could not store activity of type ${typeName}")
    }

    override fun updateTimer(timer: BabyBuddyClient.Timer?) {
        this.timer = timer
        updateVisuals()
    }

    override fun updateVisuals() {
        val now = Date()
        (timer?.start ?: now).let {
            val diff = now.time - it.time

            val seconds = diff.toInt() / 1000
            val minutes = seconds / 60
            val hours = minutes / 60

            uiCurrentTimerTime.text = "HH:MM:ss"
                .replace("HH".toRegex(), "" + hours)
                .replace("MM".toRegex(), eu.pkgsoftware.babybuddywidgets.login.Utils.padToLen("" + minutes % 60, '0', 2))
                .replace("ss".toRegex(), eu.pkgsoftware.babybuddywidgets.login.Utils.padToLen("" + seconds % 60, '0', 2))
        }
    }

    override fun store(
        timer: BabyBuddyClient.Timer,
        callback: BabyBuddyClient.RequestCallback<TimeEntry>
    ) {
        fragment.mainActivity.scope.launch {
            try {
                val result = createEntry(timer)
                timerControl.stopTimer(
                    timer,
                    object : Promise<Any, TranslatedException> {
                        override fun succeeded(s: Any?) {
                            callback.response(result)
                        }

                        override fun failed(f: TranslatedException?) {
                            callback.error(
                                f?.originalError
                                    ?: IOException("Failed to stop timer")
                            )
                        }
                    })
            }
            catch (e: Exception) {
                callback.error(e)
            }
        }
    }

    override fun name(): String {
        return this@AlternativeGenericLoggingController.typeName
    }

    override fun stopTimer(timer: BabyBuddyClient.Timer) {
        timerControl.stopTimer(
            timer,
            object : Promise<Any, TranslatedException> {
                override fun succeeded(s: Any?) {
                    storingPromise!!.succeeded(null)
                }

                override fun failed(f: TranslatedException?) {
                    storingPromise!!.failed(f)
                }
            })
    }

    override fun cancel() {
        storingPromise!!.succeeded(null)
    }

    override fun error(error: java.lang.Exception) {
        var message = "" + (error.message ?: "")
        if ((error is eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure) && (error.hasJSONMessage())) {
            message = Phrase.from(
                fragment.requireContext(),
                R.string.activity_store_failure_server_error
            )
                .put("message", "Error while storing activity")
                .put("server_message", error.jsonErrorMessages().joinToString(", "))
                .format().toString()
        }

        fragment.showQuestion(
            true,
            fragment.getString(R.string.activity_store_failure_message),
            message,
            fragment.getString(R.string.activity_store_failure_cancel),
            fragment.getString(R.string.activity_store_failure_stop_timer),
            object : DialogCallback {
                override fun call(b: Boolean) {
                    if (!b) {
                        timer?.let { stopTimer(it) }
                    } else {
                        storingPromise!!.succeeded(null)
                    }
                }
            }
        )
    }

    override fun response(response: TimeEntry?) {
        storingPromise!!.succeeded(response)
    }
}

class AlternativeSleepLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : AlternativeGenericLoggingController(fragment, childId, timerControl, SleepEntry::class) {
    override suspend fun createEntry(timer: BabyBuddyClient.Timer): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            SleepEntry::class,
            SleepEntry(
                id = 0,
                childId = childId,
                start = timer.start,
                end = maxDate(timer.start, nowServer()),
                _notes = bindings.noteEditor.text.toString()
            )
        )
    }
}

class AlternativeTummyTimeLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : AlternativeGenericLoggingController(fragment, childId, timerControl, TummyTimeEntry::class) {
    override suspend fun createEntry(timer: BabyBuddyClient.Timer): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            TummyTimeEntry::class,
            TummyTimeEntry(
                id = 0,
                childId = childId,
                start = timer.start,
                end = maxDate(timer.start, nowServer()),
                _notes = bindings.noteEditor.text.toString()
            )
        )
    }
}

class AlternativeFeedingLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : AlternativeGenericLoggingController(fragment, childId, timerControl, FeedingEntry::class) {
    val feedingBinding = FeedingLoggingEntryBinding.inflate(fragment.layoutInflater)

    override val uiCurrentTimerTime = feedingBinding.currentTimerTime
    override val uiNoteEditor = feedingBinding.noteEditor
    override val saveButton: ImageButton = feedingBinding.sendButton
    override val controlsView: View = feedingBinding.root

    override suspend fun createEntry(timer: BabyBuddyClient.Timer): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            FeedingEntry::class,
            FeedingEntry(
                id = 0,
                childId = childId,
                start = timer.start,
                end = maxDate(timer.start, nowServer()),
                _notes = feedingBinding.noteEditor.text.toString()
            )
        )
    }
}

class AlternativePumpingLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : AlternativeGenericLoggingController(fragment, childId, timerControl, PumpingEntry::class) {
    val pumpingBinding = PumpingLoggingEntryBinding.inflate(fragment.layoutInflater)

    override val uiCurrentTimerTime = pumpingBinding.currentTimerTime
    override val uiNoteEditor = pumpingBinding.noteEditor
    override val saveButton: ImageButton = pumpingBinding.sendButton
    override val controlsView: View = pumpingBinding.root

    override suspend fun createEntry(timer: BabyBuddyClient.Timer): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            PumpingEntry::class,
            PumpingEntry(
                id = 0,
                childId = childId,
                _start = timer.start,
                _end = maxDate(timer.start, nowServer()),
                amount = 0.0,
                _notes = uiNoteEditor.text.toString(),
                _legacyTime = timer.start
            )
        )
    }
}

class AlternativeDiaperLoggingController(
    val fragment: BaseFragment,
    childId: Int
) : AlternativeLoggingControls(childId) {
    val bindings = DiaperLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView = bindings.root
    override val saveButton = bindings.sendButton

    val wetLogic = SwitchButtonLogic(
        bindings.wetDisabledButton, bindings.wetEnabledButton, false
    )
    val solidLogic = SwitchButtonLogic(
        bindings.solidDisabledButton, bindings.solidDisabledButton, false
    )

    override fun storeStateForSuspend() {
    }

    override fun reset() {
    }

    override suspend fun save(): TimeEntry? {
        return null
    }
}

class AlternativeNoteLoggingController(
    val fragment: BaseFragment,
    childId: Int
) : AlternativeLoggingControls(childId) {
    val bindings = NoteLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView = bindings.root
    override val saveButton = bindings.sendButton

    override fun storeStateForSuspend() {
    }

    override fun reset() {
    }

    override suspend fun save(): TimeEntry? {
        return null
    }
}

class AlternativeLoggingButtonController(
    val fragment: BaseFragment,
    val bindings: BabyManagerAlternativeBinding,
    val controlsInterface: FragmentCallbacks,
    val child: BabyBuddyClient.Child,
    val timerControl: TimerControlInterface,
) : TimersUpdatedCallback {
    val logicMap = mapOf(
        BabyBuddyClient.EVENTS.CHANGE to SwitchButtonLogic(
            bindings.diaperDisabledButton, bindings.diaperEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.NOTE to SwitchButtonLogic(
            bindings.notesDisabledButton, bindings.notesEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.SLEEP to SwitchButtonLogic(
            bindings.sleepDisabledButton, bindings.sleepEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.FEEDING to SwitchButtonLogic(
            bindings.feedingDisabledButton, bindings.feedingEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.TUMMY_TIME to SwitchButtonLogic(
            bindings.tummyTimeDisabledButton, bindings.tummyTimeEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.PUMPING to SwitchButtonLogic(
            bindings.pumpingDisabledButton, bindings.pumpingEnabledButton, false
        ),
    )

    val loggingControllers: Map<String, AlternativeLoggingControls> = mapOf(
        BabyBuddyClient.EVENTS.CHANGE to AlternativeDiaperLoggingController(fragment, child.id),
        BabyBuddyClient.EVENTS.NOTE to AlternativeNoteLoggingController(fragment, child.id),
        BabyBuddyClient.ACTIVITIES.SLEEP to AlternativeSleepLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.TUMMY_TIME to AlternativeTummyTimeLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.FEEDING to AlternativeFeedingLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.PUMPING to AlternativePumpingLoggingController(
            fragment, child.id, timerControl
        ),
    )

    private var timerHandler: Handler? = Handler(fragment.mainActivity.mainLooper)
    private var cachedTimers = emptyArray<BabyBuddyClient.Timer>()
    private val timerModificationsBlocker = ConcurrentEventBlocker()

    init {
        loggingControllers.forEach { (activity, controller) ->
            controller.postInit()

            logicMap[activity]?.addStateListener { state, userInduced ->
                fragment.mainActivity.scope.launch {
                    timerModificationsBlocker.wait()
                    if (state) {
                        startTimerFromSwitch(controller, userInduced, activity)
                    } else {
                        stopTimerFromSwitch(controller, userInduced, activity)
                    }
                }
            }
            controller.saveButton.setOnClickListener {
                fragment.mainActivity.scope.launch {
                    timerModificationsBlocker.wait()
                    runSave(activity, controller)
                }
            }

            timerControl.registerTimersUpdatedCallback(this)
        }

        fragment.mainActivity.storage.child<AlternativeLoggingButtonControllerStoreState>(
            child.id, "loggingstate"
        )?.let {
            for ((k, logic) in logicMap.entries) {
                if (k !in BabyBuddyClient.EVENTS.ALL) continue
                if (k in it.openState) {
                    logic.state = true
                }
            }
        }

        timerHandler()
    }

    private fun startTimerFromSwitch(
        controller: AlternativeLoggingControls,
        userInduced: Boolean,
        activity: String
    ) {
        controlsInterface.insertControls(controller.controlsView)
        if (userInduced && (controller is AlternativeGenericLoggingController)) {
            cachedTimers.firstOrNull { it.name == activity }?.let {
                fragment.mainActivity.scope.launch {
                    try {
                        timerModificationsBlocker.register {
                            val newTimer =
                                AsyncPromise.call<BabyBuddyClient.Timer, TranslatedException> { promise ->
                                    timerControl.startTimer(it, promise)
                                }
                            controller.updateTimer(newTimer)
                        }
                    }
                    catch (e: AsyncPromiseFailure) {
                        (e.value as? TranslatedException)?.let {
                            fragment.showError(
                                true,
                                R.string.activity_store_failure_message,
                                it.message
                            )
                        }
                    }
                }
            }
        }
        controller.postStart()
    }

    private suspend fun stopTimerFromSwitch(
        controller: AlternativeLoggingControls,
        userInduced: Boolean,
        activity: String
    ) {
        timerModificationsBlocker.register {
            val timer = cachedTimers.firstOrNull { it.name == activity }
            if (AsyncPromise.call<Boolean, TranslatedException> { promise ->
                    var defaultSucceed = true
                    timer?.let { timer ->
                        if (timer.active && userInduced) {
                            val timeMs = nowServer().time - timer.start.time
                            if (timeMs > 10000) {
                                defaultSucceed = false;

                                val message = Phrase.from(
                                    fragment.requireContext(),
                                    R.string.cancel_timer_warning_message
                                )
                                    .put("activity", fragment.translateActivityName(activity))
                                    .format().toString()

                                fragment.showQuestion(
                                    true,
                                    fragment.getString(R.string.cancel_timer_warning_title),
                                    message,
                                    fragment.getString(R.string.cancel_timer_warning_stop),
                                    fragment.getString(R.string.cancel_timer_warning_keep),
                                    object : DialogCallback {
                                        override fun call(b: Boolean) {
                                            promise.succeeded(b)
                                        }
                                    }
                                );
                            }
                        }
                    }
                    if (defaultSucceed) {
                        promise.succeeded(true)
                    }
                }) {
                controlsInterface.removeControls(controller.controlsView)
                if (userInduced && (controller is AlternativeGenericLoggingController)) {
                    timer?.let {
                        fragment.mainActivity.scope.launch {
                            try {
                                controller.updateTimer(null)
                                AsyncPromise.call<Any, TranslatedException> { promise ->
                                    timerControl.stopTimer(it, promise)
                                }
                                controller.updateTimer(null)
                            }
                            catch (e: AsyncPromiseFailure) {
                                (e.value as? TranslatedException)?.let {
                                    fragment.showError(
                                        true,
                                        R.string.activity_store_failure_message,
                                        it.message
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun timerHandler() {
        timerHandler?.let {
            it.postDelayed(Runnable { timerHandler() }, 500)
            for (c in loggingControllers.values) {
                c.updateVisuals()
            }
        }
    }

    suspend fun runSave(activity: String, controller: AlternativeLoggingControls) {
        timerModificationsBlocker.wait()
        timerModificationsBlocker.register {
            try {
                exponentialBackoff(fragment.disconnectDialog.getInterface(), forceRetry400 = 5) {
                    logicMap[activity]?.state = false
                    val te = controller.save()
                    controller.reset()
                    storeStateForSuspend()
                    controlsInterface.updateTimeline(te)
                }
            }
            catch (e: eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    Phrase.from(
                        fragment.requireContext(),
                        R.string.activity_store_failure_server_error
                    )
                        .put(
                            "message",
                            fragment.getString(R.string.activity_store_failure_server_error_general)
                        )
                        .put("server_message", e.jsonErrorMessages().joinToString(", "))
                        .format().toString()

                )
            }
            catch (e: IOException) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    R.string.activity_store_failure_server_error_generic_ioerror
                )
            }
        }
    }

    fun storeStateForSuspend() {
        val openState = mutableListOf<String>()
        for ((name, controller) in loggingControllers) {
            controller.storeStateForSuspend()
            if (name in BabyBuddyClient.EVENTS.ALL) {
                if (logicMap[name]?.state == true) {
                    openState.add(name)
                }
            }
        }
        fragment.mainActivity.storage.child(
            child.id,
            "loggingstate",
            AlternativeLoggingButtonControllerStoreState(openState.toTypedArray())
        )
    }

    fun destroy() {
        storeStateForSuspend()
        for (controller in loggingControllers.values) {
            controlsInterface.removeControls(controller.controlsView)
        }
        for (logic in logicMap.values) {
            logic.destroy()
        }
        timerControl.unregisterTimersUpdatedCallback(this)
        timerHandler = null
    }

    override fun newTimerListLoaded(timers: Array<BabyBuddyClient.Timer>) {
        cachedTimers = timers;
        if (timerModificationsBlocker.isBlocked) return

        val toDisable =
            loggingControllers.filter { it.value is AlternativeGenericLoggingController }.map { it.key }.toMutableList()
        for (timer in timers) {
            if (!timer.active) continue
            loggingControllers[timer.name]?.let { controller ->
                if (controller is AlternativeGenericLoggingController) {
                    toDisable.remove(timer.name)

                    controller.updateTimer(timer)
                    controller.updateVisuals()
                    controlsInterface.insertControls(controller.controlsView)
                }
            }
            logicMap[timer.name]?.let { logic ->
                logic.state = true
            }
        }
        for (name in toDisable) {
            logicMap[name]?.state = false
            loggingControllers[name]?.let {
                controlsInterface.removeControls(it.controlsView)
            }
        }
    }
}
