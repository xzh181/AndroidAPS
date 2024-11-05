package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.utils.T
import app.aaps.core.utils.isRunningTest
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.CgmSourceTransaction
import app.aaps.database.transactions.TransactionGlucoseValue
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.security.SecureRandom
import java.util.Calendar
import java.util.GregorianCalendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

@Singleton
class RandomBgPlugin @Inject constructor(
    private val context: Context,
    injector: HasAndroidInjector,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val repository: AppRepository,
    private val virtualPump: VirtualPump,
    private val sp: SP,
    private val config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(R.drawable.ic_dice)
        .preferencesId(R.xml.pref_randombg)
        .pluginName(R.string.random_bg)
        .shortName(R.string.random_bg_short)
        .description(R.string.description_source_random_bg),
    aapsLogger, rh, injector
), BgSource {

    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var refreshLoop: Runnable
    private var wakeLock: PowerManager.WakeLock? = null
    private var interval = 5L // minutes

    companion object {

        const val min = 70 // mgdl
        const val max = 190 // mgdl
        const val period = 120.0 // minutes
    }

    init {
        refreshLoop = Runnable {
            updateInterval()
            handler.postDelayed(refreshLoop, T.mins(interval).msecs())
            handleNewData()
        }
    }

    private fun updateInterval() {
        interval = sp.getInt("randombg_interval_min", 5).toLong()
    }

    private val disposable = CompositeDisposable()

    override fun advancedFilteringSupported(): Boolean = true

    @SuppressLint("WakelockTimeout")
    override fun onStart() {
        super.onStart()
        updateInterval()
        val cal = GregorianCalendar()
        cal[Calendar.MILLISECOND] = 0
        cal[Calendar.SECOND] = 0
        cal[Calendar.MINUTE] -= cal[Calendar.MINUTE] % interval.toInt()
        handler.postAtTime(refreshLoop, SystemClock.uptimeMillis() + cal.timeInMillis + T.mins(interval).msecs() + 1000 - System.currentTimeMillis())
        disposable.clear()
        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAPS:RandomBgPlugin")
        wakeLock?.acquire()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refreshLoop)
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun specialEnableCondition(): Boolean {
        return isRunningTest() || virtualPump.isEnabled() && config.isEngineeringMode()
    }

    private fun handleNewData() {
        if (!isEnabled()) return

        val cal = GregorianCalendar()
        val currentMinute = cal[Calendar.MINUTE] + (cal[Calendar.HOUR_OF_DAY] % 2) * 60
        val bgMgdl = min + ((max - min) + (max - min) * sin(currentMinute / period * 2 * PI)) / 2 + (SecureRandom().nextDouble() - 0.5) * (max - min) * 0.4

        cal[Calendar.MILLISECOND] = 0
        cal[Calendar.SECOND] = 0
        cal[Calendar.MINUTE] -= cal[Calendar.MINUTE] % interval.toInt()
        val glucoseValues = mutableListOf<TransactionGlucoseValue>()
        glucoseValues += TransactionGlucoseValue(
            timestamp = cal.timeInMillis,
            value = bgMgdl,
            raw = 0.0,
            noise = null,
            trendArrow = GlucoseValue.TrendArrow.entries.shuffled().first(),
            sourceSensor = GlucoseValue.SourceSensor.RANDOM
        )
        disposable += repository.runTransactionForResult(CgmSourceTransaction(glucoseValues, emptyList(), null))
            .subscribe({ savedValues ->
                           savedValues.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted bg $it") }
                       }, { aapsLogger.error(LTag.DATABASE, "Error while saving values from Random plugin", it) }
            )
    }
}
