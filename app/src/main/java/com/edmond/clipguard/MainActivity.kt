package com.edmond.clipguard

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.graphics.drawable.Drawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.media.RingtoneManager
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // Palette
    private val bg = Color.parseColor("#F3F5F7")
    private val card = Color.WHITE
    private val ink = Color.parseColor("#1B2330")
    private val muted = Color.parseColor("#6B7380")
    private val line = Color.parseColor("#DDE2E8")
    private val accent = Color.parseColor("#0F766E")      // teal, like the check mark on the clip
    private val green = Color.parseColor("#15803D")
    private val red = Color.parseColor("#C62828")
    private val amber = Color.parseColor("#B45309")
    private val slate = Color.parseColor("#475569")
    private val grey = Color.parseColor("#64748B")

    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var watchName: TextView
    private lateinit var statusSub: TextView
    private lateinit var chipConn: TextView
    private lateinit var chipBattery: TextView
    private lateinit var mainButton: Button
    private lateinit var details: TextView

    private lateinit var macField: EditText
    private lateinit var graceField: EditText
    private lateinit var escalateField: EditText
    private val numberFields = mutableListOf<EditText>()
    private val keyFields = mutableListOf<EditText>()
    private lateinit var smsSwitch: Switch
    private lateinit var waSwitch: Switch
    private lateinit var soundName: TextView
    private lateinit var carName: TextView
    private lateinit var checksBox: LinearLayout
    private lateinit var checksText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setBackgroundDrawable(ColorDrawable(ink))
        window.statusBarColor = ink
        window.decorView.setBackgroundColor(bg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // ---------------------------------------------------------- status card
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        statusTitle = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        statusSub = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.argb(225, 255, 255, 255))
            setPadding(0, dp(4), 0, dp(14))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipConn = chip(R.drawable.ic_bluetooth)
        chipBattery = chip(R.drawable.ic_battery)
        chips.addView(chipConn)
        chips.addView(chipBattery, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        statusIcon = ImageView(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(45, 255, 255, 255))
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(statusIcon, LinearLayout.LayoutParams(dp(60), dp(60)))
        val headTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        statusSub.setPadding(0, dp(2), 0, 0)
        headTexts.addView(statusTitle)
        headTexts.addView(statusSub)
        head.addView(headTexts, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(head)
        statusCard.addView(chips, lp(top = 16))
        root.addView(statusCard)

        mainButton = bigButton()
        root.addView(mainButton, lp(top = 12))

        checksBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.parseColor("#FFF7ED"), 14, Color.parseColor("#FED7AA"))
        }
        checksBox.addView(TextView(this).apply {
            text = "Needs attention"; textSize = 14f; setTextColor(amber)
            setTypeface(typeface, Typeface.BOLD)
        })
        checksText = TextView(this).apply { textSize = 14f; setTextColor(ink); setPadding(0, dp(4), 0, 0) }
        checksBox.addView(checksText)
        root.addView(checksBox, lp(top = 12))

        // ---------------------------------------------------------- contacts
        root.addView(sectionLabel("Emergency contacts", R.drawable.ic_person))
        val contactsCard = cardBox()
        val slots = Prefs.contactSlots(this)
        slots.forEachIndexed { i, ct ->
            if (i > 0) contactsCard.addView(divider())
            contactsCard.addView(TextView(this).apply {
                text = "Contact ${i + 1}"; textSize = 13f; setTextColor(muted)
                setTypeface(typeface, Typeface.BOLD)
                setCompoundDrawablesRelative(icon(R.drawable.ic_person, muted, 16), null, null, null)
                compoundDrawablePadding = dp(6)
            })
            val num = field(ct.number, InputType.TYPE_CLASS_PHONE, "Phone number, e.g. +32 4xx xx xx xx")
            val key = field(ct.waKey, InputType.TYPE_CLASS_NUMBER, "WhatsApp key (optional)")
            key.textSize = 15f
            numberFields += num
            keyFields += key
            contactsCard.addView(num)
            contactsCard.addView(key)
        }
        root.addView(contactsCard)

        root.addView(sectionLabel("How to alert them", R.drawable.ic_chat))
        val channels = cardBox()
        smsSwitch = switchRow("SMS", "Works without mobile data", Prefs.useSms(this))
        waSwitch = switchRow("WhatsApp", "Via CallMeBot · needs mobile data", Prefs.useWhatsApp(this))
        channels.addView(smsSwitch.parent as View)
        channels.addView(divider())
        channels.addView(waSwitch.parent as View)
        channels.addView(hint("WhatsApp key: each contact saves +34 644 91 07 79, sends it " +
            "\"I allow callmebot to send me messages\" on WhatsApp, and gets a key back. " +
            "Enter that key next to their number."))
        root.addView(channels)

        // ---------------------------------------------------------- car
        root.addView(sectionLabel("Your car", R.drawable.ic_car))
        val carCard = cardBox()
        val carRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        carName = TextView(this).apply { textSize = 16f; setTextColor(ink) }
        carRow.addView(carName, LinearLayout.LayoutParams(0, -2, 1f))
        carRow.addView(smallButton("Choose") { pickCar() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setPadding(dp(18), 0, dp(18), 0)
        })
        carCard.addView(carRow)
        carCard.addView(hint("When your phone joins the car's Bluetooth and ClipGuard can't see the clip within 90 seconds, you get a warning."))
        root.addView(carCard)
        showCarName()

        // ---------------------------------------------------------- watch
        root.addView(sectionLabel("Your watch", R.drawable.ic_watch))
        val watchCard = cardBox()
        val watchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        watchName = TextView(this).apply { textSize = 16f; setTextColor(ink) }
        watchRow.addView(watchName, LinearLayout.LayoutParams(0, -2, 1f))
        watchRow.addView(smallButton("Choose") { pickWatch() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setPadding(dp(18), 0, dp(18), 0)
        })
        watchCard.addView(watchRow)
        watchCard.addView(hint("Catches a phone left in the car: if the clip is closed, the car is off, and your watch " +
            "loses this phone, you walked away without it. The alarm starts and your contacts are told the phone is in the car."))
        root.addView(watchCard)
        showWatchName()

        // ---------------------------------------------------------- sound
        root.addView(sectionLabel("Alarm sound", R.drawable.ic_music))
        val soundCard = cardBox()
        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        soundName = TextView(this).apply { textSize = 16f; setTextColor(ink) }
        soundRow.addView(soundName, LinearLayout.LayoutParams(0, -2, 1f))
        soundRow.addView(smallButton("Change") { pickSound() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setPadding(dp(18), 0, dp(18), 0)
        })
        soundCard.addView(soundRow)
        soundCard.addView(hint("Plays at full alarm volume, also when the phone is on silent."))
        root.addView(soundCard)
        showSoundName()

        // ---------------------------------------------------------- timing
        root.addView(sectionLabel("Alarm timing", R.drawable.ic_alarm))
        val timingCard = cardBox()
        graceField = field(Prefs.graceSec(this).toString(), InputType.TYPE_CLASS_NUMBER, "120")
        escalateField = field(Prefs.escalateSec(this).toString(), InputType.TYPE_CLASS_NUMBER, "60")
        timingCard.addView(row("Loud alarm after", graceField, "seconds"))
        timingCard.addView(divider())
        timingCard.addView(row("SMS to contacts after", escalateField, "more seconds"))
        root.addView(timingCard)

        root.addView(smallButton("Save settings", filled = true) { if (save()) toast("Saved") }, lp(top = 10))

        // ---------------------------------------------------------- tests
        root.addView(sectionLabel("Tests", R.drawable.ic_play))
        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tests.addView(smallButton("Test alarm", iconRes = R.drawable.ic_alarm) { sendToService(ClipService.ACTION_TEST_ALARM) },
            LinearLayout.LayoutParams(0, -2, 1f))
        tests.addView(smallButton("Test message", iconRes = R.drawable.ic_chat) { sendToService(ClipService.ACTION_TEST_SMS) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        root.addView(tests)
        root.addView(smallButton("Allow running in background") { askBatteryExemption() }, lp(top = 10))

        // ---------------------------------------------------------- advanced
        root.addView(sectionLabel("Advanced", R.drawable.ic_tune))
        val adv = cardBox()
        adv.addView(TextView(this).apply { text = "Clip Bluetooth address"; textSize = 13f; setTextColor(muted) })
        macField = field(Prefs.mac(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, Prefs.DEFAULT_MAC)
        adv.addView(macField)
        details = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        adv.addView(details)
        root.addView(adv)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root)
        })
        requestPermissions(Prefs.requiredPermissions(), 1)
        HealthReceiver.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- status rendering

    private fun render() {
        val s = ClipService.snapshot
        val color: Int
        val title: String
        val sub: String
        val ic: Int
        when {
            s.problem != null -> { color = amber; ic = R.drawable.ic_warning; title = "Needs attention"; sub = s.problem }
            !s.running -> { color = grey; ic = R.drawable.ic_stop; title = "Not watching"; sub = "Tap Start to watch the clip" }
            s.stage == 1 -> { color = red; ic = R.drawable.ic_warning; title = "Clip still closed!"; sub = "Go back to the car" }
            s.stage == 2 -> { color = red; ic = R.drawable.ic_bell; title = "ALARM"; sub = "Child may still be in the car" }
            s.stage == 3 -> { color = red; ic = R.drawable.ic_bell; title = "ALARM"; sub = "Emergency contacts were alerted" }
            !s.connected -> { color = slate; ic = R.drawable.ic_bluetooth; title = "Looking for the clip"; sub = "Close the clip and keep the phone nearby" }
            s.clipClosed == true -> { color = green; ic = R.drawable.ic_shield_check; title = "Child buckled in"; sub = "Clip closed · you'll be warned if you walk away" }
            s.clipClosed == false -> { color = accent; ic = R.drawable.ic_lock_open; title = "Clip open"; sub = "No alarm – ready for the next trip" }
            else -> { color = slate; ic = R.drawable.ic_bluetooth; title = "Connected"; sub = "Reading the clip…" }
        }
        if (statusIcon.tag != ic) {
            statusIcon.setImageDrawable(icon(ic, Color.WHITE, 36))
            statusIcon.tag = ic
        }
        (statusCard.background as? GradientDrawable)?.setColor(color)
            ?: run { statusCard.background = rounded(color, 20) }
        statusTitle.text = title
        statusSub.text = sub

        chipConn.text = when {
            !s.running -> "Off"
            s.connected -> "Connected"
            else -> "Not connected"
        }
        chipBattery.visibility = if (s.battery != null) View.VISIBLE else View.GONE
        chipBattery.text = "Battery ${s.battery ?: 0}%"

        when {
            s.stage > 0 -> styleBig("Child is out – stop alarm", Color.WHITE, red, null, R.drawable.ic_check_circle)
            s.running -> styleBig("Stop watching", ink, card, line, R.drawable.ic_stop)
            else -> styleBig("Start watching", Color.WHITE, accent, null, R.drawable.ic_play)
        }

        val alarming = s.stage >= 2
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(alarming)
            setTurnScreenOn(alarming)
        }

        val problems = HealthReceiver.problems(this)
        checksBox.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        checksText.text = problems.joinToString("\n") { "• $it" }

        details.text = "Raw clip value: ${s.raw}\nLast event: ${s.lastEvent.ifEmpty { "-" }}"
    }

    private fun onMainButton() {
        val s = ClipService.snapshot
        when {
            s.stage > 0 -> sendToService(ClipService.ACTION_DISMISS)
            s.running -> {
                Prefs.setMonitoring(this, false)
                stopService(Intent(this, ClipService::class.java))
            }
            else -> startMonitoring()
        }
        handler.postDelayed({ render() }, 300)
    }

    // ---------------------------------------------------------------- actions

    private fun save(): Boolean {
        val mac = macField.text.toString().trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            toast("Invalid Bluetooth address"); return false
        }
        val grace = graceField.text.toString().toIntOrNull()?.coerceIn(10, 1800) ?: 120
        val esc = escalateField.text.toString().toIntOrNull()?.coerceIn(10, 1800) ?: 60
        val list = numberFields.indices.map {
            Prefs.Contact(numberFields[it].text.toString(), keyFields[it].text.toString())
        }
        Prefs.save(this, mac, grace, esc, list.map { it.number.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        Prefs.saveContacts(this, list)
        Prefs.setChannels(this, smsSwitch.isChecked, waSwitch.isChecked)
        if (!smsSwitch.isChecked && !waSwitch.isChecked) toast("Note: no alert channel is on")
        return true
    }

    private fun startMonitoring() {
        if (!save()) return
        if (!Prefs.hasBluetoothPerms(this)) {
            toast("Allow 'Nearby devices' so the app can reach the clip")
            requestPermissions(Prefs.requiredPermissions(), 1)
            return
        }
        Prefs.setMonitoring(this, true)
        HealthReceiver.schedule(this)
        startForegroundService(Intent(this, ClipService::class.java))
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Tip: tap 'Allow running in background' for reliable alarms")
        }
    }

    private fun sendToService(action: String) {
        if (!Prefs.hasBluetoothPerms(this)) {
            toast("Allow 'Nearby devices' first"); return
        }
        save()
        startForegroundService(Intent(this, ClipService::class.java).setAction(action))
    }

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun pickSound() {
        val current = Prefs.alarmSound(this)?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        try { startActivityForResult(i, 42) } catch (e: Exception) { toast("No sound picker on this phone") }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val isDefault = uri == null || uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        Prefs.setAlarmSound(this, if (isDefault) null else uri.toString())
        showSoundName()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun pickCar() {
        if (!Prefs.hasBluetoothPerms(this)) { toast("Allow 'Nearby devices' first"); return }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val bonded = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val devices = bonded.filter { !it.address.equals(Prefs.mac(this), ignoreCase = true) }
            .sortedBy { it.name ?: it.address }
        val labels = listOf("Auto-detect (any car kit)") + devices.map { it.name ?: it.address }
        android.app.AlertDialog.Builder(this)
            .setTitle("Which Bluetooth is your car?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) Prefs.setCar(this, null, null)
                else devices[which - 1].let { Prefs.setCar(this, it.address, it.name ?: it.address) }
                showCarName()
            }
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun pickWatch() {
        if (!Prefs.hasBluetoothPerms(this)) { toast("Allow 'Nearby devices' first"); return }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val bonded = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val devices = bonded.filter { !it.address.equals(Prefs.mac(this), ignoreCase = true) }
            .sortedWith(compareByDescending<android.bluetooth.BluetoothDevice> {
                (it.name ?: "").contains("garmin", true) || (it.name ?: "").contains("fenix", true) ||
                    (it.name ?: "").contains("forerunner", true) || (it.name ?: "").contains("venu", true)
            }.thenBy { it.name ?: it.address })
        if (devices.isEmpty()) { toast("No paired devices found"); return }
        val labels = listOf("Don't use a watch") + devices.map { it.name ?: it.address }
        android.app.AlertDialog.Builder(this)
            .setTitle("Which device is your watch?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) Prefs.setWatch(this, null, null)
                else devices[which - 1].let { Prefs.setWatch(this, it.address, it.name ?: it.address) }
                showWatchName()
            }
            .show()
    }

    private fun showWatchName() {
        watchName.text = Prefs.watchName(this) ?: "Not used"
    }

    private fun showCarName() {
        carName.text = Prefs.carName(this) ?: "Auto-detect (any car kit)"
    }

    private fun showSoundName() {
        val uri = Prefs.alarmSound(this)?.let { Uri.parse(it) }
        soundName.text = if (uri == null) "Phone's default alarm" else
            try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Custom sound" }
            catch (_: Exception) { "Custom sound" }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- view helpers

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pressable(color: Int, radiusDp: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), rounded(color, radiusDp, stroke), null)

    private fun icon(res: Int, tint: Int, sizeDp: Int): Drawable =
        getDrawable(res)!!.mutate().apply {
            setTint(tint)
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    private fun chip(iconRes: Int) = TextView(this).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(12), dp(5))
        background = rounded(Color.argb(50, 255, 255, 255), 14)
        setCompoundDrawablesRelative(icon(iconRes, Color.WHITE, 16), null, null, null)
        compoundDrawablePadding = dp(6)
    }

    private fun sectionLabel(t: String, iconRes: Int) = TextView(this).apply {
        text = t.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(muted)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(24), 0, dp(8))
        setCompoundDrawablesRelative(icon(iconRes, accent, 18), null, null, null)
        compoundDrawablePadding = dp(8)
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(card, 16, line)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(6), 0, 0)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    private fun field(value: String, type: Int, hintText: String) = EditText(this).apply {
        setText(value)
        hint = hintText
        inputType = type
        setSingleLine()
        textSize = 17f
        setTextColor(ink)
        backgroundTintList = ColorStateList.valueOf(accent)
    }

    private fun row(label: String, f: EditText, unit: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(ink) },
            LinearLayout.LayoutParams(0, -2, 1f))
        f.gravity = Gravity.END
        f.minEms = 3
        addView(f, LinearLayout.LayoutParams(-2, -2))
        addView(TextView(context).apply {
            text = unit; textSize = 13f; setTextColor(muted); setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun switchRow(title: String, sub: String, on: Boolean): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(ink) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(muted) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = on
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.parseColor("#F1F5F9")))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#99D5CF"), Color.parseColor("#CBD5E1")))
        }
        row.addView(sw)
        return sw
    }

    private fun bigButton() = Button(this).apply {
        isAllCaps = false
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(60)
        stateListAnimator = null
        setOnClickListener { onMainButton() }
    }

    private var bigStyleKey = ""

    private fun styleBig(t: String, textColor: Int, fill: Int, stroke: Int?, iconRes: Int) {
        if (bigStyleKey == t) return
        bigStyleKey = t
        mainButton.text = t
        mainButton.setTextColor(textColor)
        mainButton.background = pressable(fill, 16, stroke)
        mainButton.setCompoundDrawablesRelative(icon(iconRes, textColor, 24), null, null, null)
        mainButton.compoundDrawablePadding = dp(10)
        mainButton.setPadding(dp(24), 0, dp(24), 0)
    }

    private fun smallButton(t: String, filled: Boolean = false, iconRes: Int? = null, action: () -> Unit) = Button(this).apply {
        text = t
        if (iconRes != null) {
            setCompoundDrawablesRelative(icon(iconRes, if (filled) Color.WHITE else accent, 20), null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(16), 0, dp(16), 0)
        }
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = dp(48)
        setTextColor(if (filled) Color.WHITE else accent)
        background = if (filled) pressable(accent, 12) else pressable(card, 12, line)
        setOnClickListener { action() }
        layoutParams = ViewGroup.LayoutParams(-1, -2)
    }
}
