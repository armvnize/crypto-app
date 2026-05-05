package com.armanize.wallexportfolioadvisor

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val df = DecimalFormat("#,###")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("portfolio", MODE_PRIVATE)
        askNotificationPermission()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 44, 34, 34)
            setBackgroundColor(0xFF070B16.toInt())
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(0, 14, 0, 6)
        }

        fun input(text: String): EditText = EditText(this).apply {
            setText(text)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            setSingleLine(true)
        }

        val title = TextView(this).apply {
            text = "Wallex Portfolio Advisor"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val subtitle = TextView(this).apply {
            text = "سبدگردانی نیمه‌خودکار: نوتیف خرید/فروش کم‌ریسک‌تر"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 8, 0, 20)
        }

        val tomanInput = input(prefs.getLong("toman", 41680000L).toString())
        val usdtInput = input(prefs.getFloat("usdt", 0f).toString())
        val btcInput = input(prefs.getFloat("btc", 0f).toString())
        val solInput = input(prefs.getFloat("sol", 0f).toString())
        val intervalInput = input(prefs.getLong("interval", 30L).toString())

        val riskSpinner = Spinner(this)
        val riskItems = arrayOf("Conservative - کم‌ریسک", "Balanced - متوسط", "Aggressive - تهاجمی")
        riskSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, riskItems)
        riskSpinner.setSelection(prefs.getInt("risk", 0))

        val info = TextView(this).apply {
            text = """
روش کار:
• اپ از والکس قیمت USDT/BTC/SOL را می‌گیرد.
• موجودی را دستی وارد می‌کنی؛ API حساب نمی‌گیرد تا امن بماند.
• وقتی ستاپ کم‌ریسک‌تر فعال شود، نوتیف می‌دهد:
  چی بخری/بفروشی + حدود مبلغ/مقدار
• مناسب نت داخلی ایران، بدون Firebase و بدون سرویس خارجی.
            """.trimIndent()
            textSize = 14f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(0, 18, 0, 18)
        }

        val saveBtn = Button(this).apply { text = "SAVE PORTFOLIO" }
        val startBtn = Button(this).apply { text = "START ADVISOR" }
        val stopBtn = Button(this).apply { text = "STOP" }

        val status = TextView(this).apply {
            text = "وضعیت: خاموش"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 20, 0, 0)
        }

        fun savePrefs() {
            prefs.edit()
                .putLong("toman", tomanInput.text.toString().toDoubleOrNull()?.toLong() ?: 41680000L)
                .putFloat("usdt", usdtInput.text.toString().toFloatOrNull() ?: 0f)
                .putFloat("btc", btcInput.text.toString().toFloatOrNull() ?: 0f)
                .putFloat("sol", solInput.text.toString().toFloatOrNull() ?: 0f)
                .putLong("interval", intervalInput.text.toString().toLongOrNull() ?: 30L)
                .putInt("risk", riskSpinner.selectedItemPosition)
                .apply()
        }

        saveBtn.setOnClickListener {
            savePrefs()
            Toast.makeText(this, "سبد ذخیره شد", Toast.LENGTH_SHORT).show()
        }

        startBtn.setOnClickListener {
            savePrefs()
            val intent = Intent(this, PortfolioMonitorService::class.java).apply {
                action = "START"
            }
            ContextCompat.startForegroundService(this, intent)
            status.text = "وضعیت: روشن | سرمایه نقد: ${df.format(prefs.getLong("toman", 0L))} تومان"
        }

        stopBtn.setOnClickListener {
            val intent = Intent(this, PortfolioMonitorService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            status.text = "وضعیت: خاموش"
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(label("موجودی تومان"))
        root.addView(tomanInput)
        root.addView(label("موجودی USDT"))
        root.addView(usdtInput)
        root.addView(label("موجودی BTC"))
        root.addView(btcInput)
        root.addView(label("موجودی SOL"))
        root.addView(solInput)
        root.addView(label("فاصله چک قیمت - ثانیه"))
        root.addView(intervalInput)
        root.addView(label("ریسک"))
        root.addView(riskSpinner)
        root.addView(info)
        root.addView(saveBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(status)

        setContentView(root)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}