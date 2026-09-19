package com.example.trajectoryoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_START
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, i)
            status.text = "Análise iniciada. Agora abra o app/jogo capturado. Para parar, use a notificação."
        } else {
            status.text = "Captura de tela não autorizada."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "Falha ao carregar OpenCV", Toast.LENGTH_LONG).show()
        }
        requestNotificationPermission()
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(11, 15, 18))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Trajectory Overlay Lab"
            textSize = 25f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Captura a tela autorizada, detecta mesa/bolas e desenha trajetórias em um overlay transparente. Protótipo experimental para análise/treino."
            textSize = 14f
            setTextColor(Color.rgb(185, 199, 210))
            setPadding(0, dp(6), 0, dp(14))
        })

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(button("1. Permitir overlay") { openOverlayPermission() })
        buttonRow.addView(button("2. Iniciar análise") { startCapture() })
        buttonRow.addView(button("Parar") { stopCapture() })
        root.addView(buttonRow)

        status = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) "Overlay permitido." else "Primeiro conceda a permissão de overlay."
            textSize = 13f
            setTextColor(Color.rgb(124, 203, 255))
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        root.addView(check("Trajetórias diretas", Prefs.showDirect(this)) { Prefs.setDirect(this, it) })
        root.addView(check("Bank shots (1 tabela)", Prefs.showBanks(this)) { Prefs.setBanks(this, it) })
        root.addView(check("Trajetória da branca após a colisão", Prefs.showSecondary(this)) { Prefs.setSecondary(this, it) })
        root.addView(check("Mostrar várias opções ao mesmo tempo", Prefs.showAll(this)) { Prefs.setAll(this, it) })

        root.addView(TextView(this).apply {
            text = "Sensibilidade de detecção das bolas"
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, 0)
        })
        val sensText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            text = "${Prefs.sensitivity(this)} (menor = detecta mais círculos)"
        }
        val seek = SeekBar(this).apply {
            max = 20
            progress = Prefs.sensitivity(this@MainActivity) - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 10 + progress
                    Prefs.setSensitivity(this@MainActivity, value)
                    sensText.text = "$value (menor = detecta mais círculos)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seek)
        root.addView(sensText)

        root.addView(TextView(this).apply {
            text = "Como usar\n1) Deixe o celular em modo paisagem.\n2) Toque em ‘Permitir overlay’.\n3) Volte e toque em ‘Iniciar análise’.\n4) No diálogo de compartilhamento, selecione o app que você quer analisar (quando essa opção estiver disponível).\n5) Abra a mesa. As linhas aparecem automaticamente.\n\nSe a detecção pegar círculos falsos, aumente a sensibilidade para 20–25. Se não detectar bolas suficientes, diminua para 13–17."
            textSize = 13f
            setTextColor(Color.rgb(190, 200, 208))
            setPadding(0, dp(18), 0, dp(8))
        })
        return scroll
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
    }

    private fun check(text: String, checked: Boolean, changed: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setOnCheckedChangeListener { _, v -> changed(v) }
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Conceda a permissão de overlay primeiro."
            openOverlayPermission()
            return
        }
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureOverlayService::class.java).apply { action = CaptureOverlayService.ACTION_STOP })
        status.text = "Análise parada."
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
