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
    private var openCvReady = false

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_START
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, i)
            status.text = "Captura autorizada. Abra a mesa e veja o diagnóstico no topo do overlay."
        } else {
            status.text = "Captura de tela não autorizada."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        Prefs.applyV07Defaults(this)
        openCvReady = OpenCVLoader.initLocal()
        requestNotificationPermission()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateLocalStatus()
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
            text = "Trajectory Overlay Lab v0.7"
            textSize = 25f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "v0.7: detector de bolas muito mais restrito. Só aceita círculos com tamanho de bola real, dentro do pano e cercados pelo feltro; círculos falsos de HUD/decoração são rejeitados."
            textSize = 14f
            setTextColor(Color.rgb(185, 199, 210))
            setPadding(0, dp(6), 0, dp(14))
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("1. Permitir overlay") { openOverlayPermission() })
        row1.addView(button("2. Testar overlay") { testOverlay() })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("3. Iniciar análise") { startCapture() })
        row2.addView(button("Parar") { stopCapture() })
        root.addView(row2)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(124, 203, 255))
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)
        updateLocalStatus()

        root.addView(check("Trajetórias diretas", Prefs.showDirect(this)) { Prefs.setDirect(this, it) })
        root.addView(check("Bank shots (1 tabela)", Prefs.showBanks(this)) { Prefs.setBanks(this, it) })
        root.addView(check("Trajetória da branca após a colisão", Prefs.showSecondary(this)) { Prefs.setSecondary(this, it) })
        root.addView(check("Mostrar várias opções ao mesmo tempo", Prefs.showAll(this)) { Prefs.setAll(this, it) })
        root.addView(check("Mostrar diagnóstico visual (mesa/bolas)", Prefs.visualDebug(this)) { Prefs.setVisualDebug(this, it) })

        root.addView(TextView(this).apply {
            text = "Sensibilidade de detecção das bolas"
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, 0)
        })
        val sensText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            text = "${Prefs.sensitivity(this@MainActivity)} (menor = detecta mais)"
        }
        val seek = SeekBar(this).apply {
            max = 20
            progress = (Prefs.sensitivity(this@MainActivity) - 10).coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 10 + progress
                    Prefs.setSensitivity(this@MainActivity, value)
                    sensText.text = "$value (menor = detecta mais)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(seek)
        root.addView(sensText)

        root.addView(TextView(this).apply {
            text = "Diagnóstico esperado\n• Teste: aparece um X azul, texto e linhas de exemplo por 8 s.\n• Durante a análise, puxe a notificação Trajectory Overlay e toque em ‘Salvar print’.\n• O app salva na Galeria/Pictures/TrajectoryOverlay uma imagem da tela capturada com mesa, bolas e trajetórias marcadas.\n• Envie essa imagem para eu calibrar o detector.\n• Deixe ‘Mostrar diagnóstico visual’ desligado para a tela ficar limpa."
            textSize = 13f
            setTextColor(Color.rgb(190, 200, 208))
            setPadding(0, dp(18), 0, dp(8))
        })
        return scroll
    }

    private fun updateLocalStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        status.text = "Overlay: ${if (overlayOk) "OK" else "SEM PERMISSÃO"} • OpenCV: ${if (openCvReady) "OK" else "FALHOU"}"
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

    private fun testOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Conceda a permissão de overlay primeiro."
            openOverlayPermission()
            return
        }
        startService(Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_TEST
        })
        status.text = "Teste iniciado por 8 segundos. Saia do app e veja se o X/linhas aparecem."
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Conceda a permissão de overlay primeiro."
            openOverlayPermission()
            return
        }
        if (!openCvReady) {
            status.text = "OpenCV não carregou; reinstale a versão mais recente do APK."
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
