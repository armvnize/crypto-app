package com.armanize.wallexportfolioadvisor

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class PortfolioMonitorService : Service() {

    private val client = OkHttpClient()
    private val df = DecimalFormat("#,###")
    private val pf = DecimalFormat("#,##0.######")
    private var running = false
    private var lastSignalKey = ""
    private val usdtHistory = mutableListOf<Double>()
    private val btcHistory = mutableListOf<Double>()
    private val solHistory = mutableListOf<Double>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        running = true
        startForeground(1, serviceNotification("در حال چک کردن والکس..."))

        thread {
            while (running) {
                try {
                    val prices = getWallexPrices()
                    pushHistory(prices)
                    val signal = analyze(prices)

                    updateForeground(prices, signal)

                    if (signal.notify && signal.key != lastSignalKey) {
                        lastSignalKey = signal.key
                        notifyUser(signal.title, signal.message)
                    }

                } catch (e: Exception) {
                    notifyLow("خطا در دریافت دیتا", e.message ?: "Unknown error")
                }

                val prefs = getSharedPreferences("portfolio", MODE_PRIVATE)
                val interval = prefs.getLong("interval", 30L).coerceAtLeast(15L)
                Thread.sleep(interval * 1000)
            }
        }
    }

    private fun getWallexPrices(): Prices {
        val request = Request.Builder()
            .url("https://api.wallex.ir/v1/markets")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)
                .getJSONObject("result")
                .getJSONObject("symbols")

            fun last(symbol: String): Double {
                return json.getJSONObject(symbol)
                    .getJSONObject("stats")
                    .getDouble("lastPrice")
            }

            return Prices(
                usdt = last("USDTTMN"),
                btc = runCatching { last("BTCTMN") }.getOrElse { last("BTCUSDT") * last("USDTTMN") },
                sol = runCatching { last("SOLTMN") }.getOrElse { last("SOLUSDT") * last("USDTTMN") }
            )
        }
    }

    private fun pushHistory(p: Prices) {
        usdtHistory.add(p.usdt)
        btcHistory.add(p.btc)
        solHistory.add(p.sol)
        if (usdtHistory.size > 120) usdtHistory.removeAt(0)
        if (btcHistory.size > 120) btcHistory.removeAt(0)
        if (solHistory.size > 120) solHistory.removeAt(0)
    }

    private fun analyze(p: Prices): Signal {
        val prefs = getSharedPreferences("portfolio", MODE_PRIVATE)

        val toman = prefs.getLong("toman", 41680000L)
        val usdt = prefs.getFloat("usdt", 0f).toDouble()
        val btc = prefs.getFloat("btc", 0f).toDouble()
        val sol = prefs.getFloat("sol", 0f).toDouble()
        val risk = prefs.getInt("risk", 0)

        val totalValue = toman + usdt * p.usdt + btc * p.btc + sol * p.sol
        val usdtPct = if (totalValue > 0) (usdt * p.usdt / totalValue) * 100 else 0.0
        val cryptoPct = if (totalValue > 0) ((btc * p.btc + sol * p.sol) / totalValue) * 100 else 0.0

        val usdtRsi = rsi(usdtHistory, 14)
        val usdtMomentum = momentum(usdtHistory, 5)
        val btcMomentum = momentum(btcHistory, 5)
        val solMomentum = momentum(solHistory, 5)

        val buyUnit = when (risk) {
            0 -> (totalValue * 0.20).toLong()
            1 -> (totalValue * 0.30).toLong()
            else -> (totalValue * 0.40).toLong()
        }.coerceAtMost(toman)

        val solUnit = (buyUnit * 0.35).toLong()
        val btcUnit = buyUnit - solUnit

        val usdtAmountToBuy = if (p.usdt > 0) buyUnit / p.usdt else 0.0
        val btcAmountToBuy = if (p.btc > 0) btcUnit / p.btc else 0.0
        val solAmountToBuy = if (p.sol > 0) solUnit / p.sol else 0.0

        val canAnalyze = usdtHistory.size >= 21 && btcHistory.size >= 21 && solHistory.size >= 21

        if (!canAnalyze) {
            return Signal(false, "WAIT", "در حال جمع‌آوری دیتا", "هنوز دیتا کافی نیست.")
        }

        // 1) Low-risk USDT re-entry zones after panic cool-down
        if (toman > 0 && p.usdt in 175000.0..178000.0 && usdtMomentum <= 0.15 && usdtRsi < 68) {
            return Signal(
                true,
                "BUY_USDT_ZONE1",
                "خرید پله اول تتر",
                "قیمت USDT: ${df.format(p.usdt)} تومان\nپیشنهاد: حدود ${df.format(buyUnit)} تومان تتر بخر\nتقریباً: ${pf.format(usdtAmountToBuy)} USDT\nریسک: کم‌تر | دلیل: ورود به زون 178-175 و مومنتوم آرام"
            )
        }

        if (toman > 0 && p.usdt in 170000.0..173000.0 && usdtRsi < 62) {
            return Signal(
                true,
                "BUY_USDT_ZONE2",
                "خرید پله دوم تتر",
                "قیمت USDT: ${df.format(p.usdt)} تومان\nپیشنهاد: حدود ${df.format(min(toman, (totalValue * 0.35).toLong()))} تومان تتر بخر\nریسک: جذاب‌تر ولی همچنان پله‌ای"
            )
        }

        // 2) Crypto rotation: USDT falling while BTC/SOL stabilize
        if (toman > 0 && p.usdt < 176000 && usdtMomentum < 0 && btcMomentum >= -0.10 && solMomentum >= -0.15) {
            return Signal(
                true,
                "BUY_BTC_SOL_ROTATION",
                "ورود پله‌ای BTC/SOL",
                "شرط چرخش پول فعال شد\nUSDT: ${df.format(p.usdt)} تومان\nپیشنهاد کل خرید: ${df.format(buyUnit)} تومان\nBTC: ${df.format(btcUnit)} تومان ≈ ${pf.format(btcAmountToBuy)} BTC\nSOL: ${df.format(solUnit)} تومان ≈ ${pf.format(solAmountToBuy)} SOL\nریسک: متوسط | دلیل: افت تتر + ثبات نسبی کریپتو"
            )
        }

        // 3) Sell USDT when USDT overheats
        if (usdt > 0 && p.usdt >= 185000 && usdtRsi > 72 && usdtMomentum <= 0.10) {
            val sellUsdt = max(5.0, usdt * 0.50)
            return Signal(
                true,
                "SELL_USDT_OVERHEAT",
                "فروش پله‌ای تتر",
                "USDT وارد ناحیه داغ شده: ${df.format(p.usdt)} تومان\nپیشنهاد: حدود ${pf.format(sellUsdt)} تتر بفروش\nارزش تقریبی: ${df.format(sellUsdt * p.usdt)} تومان\nدلیل: RSI بالا + کند شدن مومنتوم"
            )
        }

        // 4) Take profit on SOL if it runs too hard
        if (sol > 0 && solMomentum > 1.8 && cryptoPct > 25) {
            val sellSol = sol * 0.35
            return Signal(
                true,
                "SELL_SOL_SPIKE",
                "سیو سود SOL",
                "SOL مومنتوم تند گرفته\nپیشنهاد: حدود ${pf.format(sellSol)} SOL بفروش\nارزش تقریبی: ${df.format(sellSol * p.sol)} تومان\nدلیل: جلوگیری از پس‌دادن سود"
            )
        }

        // 5) Defensive sell crypto if USDT spikes hard and crypto weakens
        if ((btc > 0 || sol > 0) && p.usdt > 183000 && usdtMomentum > 0.25 && btcMomentum < -0.15) {
            val sellBtc = btc * 0.30
            val sellSol = sol * 0.30
            return Signal(
                true,
                "DEFENSIVE_CRYPTO_SELL",
                "کاهش ریسک کریپتو",
                "تتر در حال جهش و BTC ضعیف است\nپیشنهاد: 30٪ از BTC/SOL را سبک کن\nBTC: ${pf.format(sellBtc)}\nSOL: ${pf.format(sellSol)}\nدلیل: افزایش ترس بازار"
            )
        }

        return Signal(
            false,
            "NO_ACTION",
            "فعلاً کاری نکن",
            "USDT: ${df.format(p.usdt)} | BTC: ${df.format(p.btc)} | SOL: ${df.format(p.sol)}\nسبد: تومان ${df.format(toman)} | USDT ${pf.format(usdt)} | BTC ${pf.format(btc)} | SOL ${pf.format(sol)}"
        )
    }

    private fun momentum(values: List<Double>, lookback: Int): Double {
        if (values.size <= lookback) return 0.0
        val old = values[values.size - 1 - lookback]
        val last = values.last()
        return if (old == 0.0) 0.0 else ((last - old) / old) * 100
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in values.size - period until values.size) {
            val diff = values[i] - values[i - 1]
            if (diff > 0) gains += diff else losses += -diff
        }
        if (losses == 0.0) return 100.0
        val rs = (gains / period) / (losses / period)
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun serviceNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "portfolio_service")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Portfolio Advisor روشن است")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateForeground(p: Prices, signal: Signal) {
        val n = NotificationCompat.Builder(this, "portfolio_service")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("USDT ${df.format(p.usdt)} | BTC/SOL OK")
            .setContentText(signal.title)
            .setOngoing(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun notifyUser(title: String, message: String) {
        val n = NotificationCompat.Builder(this, "portfolio_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentText(message.take(90))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    private fun notifyLow(title: String, message: String) {
        val n = NotificationCompat.Builder(this, "portfolio_service")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message.take(90))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    "portfolio_service",
                    "Portfolio Background Monitor",
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    "portfolio_alerts",
                    "Portfolio Buy/Sell Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class Prices(val usdt: Double, val btc: Double, val sol: Double)
    data class Signal(val notify: Boolean, val key: String, val title: String, val message: String)
}