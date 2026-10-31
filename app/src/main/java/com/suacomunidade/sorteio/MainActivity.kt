package com.suacomunidade.sorteio

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.suacomunidade.sorteio.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            WebView.enableSlowWholeDocumentDraw()
        }
    }

    internal lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var printWebView: WebView? = null // Mantém referência para evitar Garbage Collection

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permissão tratada
    }

    // Lida com o resultado da escolha do arquivo CSV
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val uris: Array<Uri>? = uri?.let { arrayOf(it) }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()
        requestNotificationPermission()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(enabled = true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Comunidade 11 Notificações"
            val descriptionText = "Alertas de Eventos e Aniversários"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("COMUNIDADE_11", name, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = android.graphics.Color.rgb(140, 107, 69)
                enableVibration(true)
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null)
                setShowBadge(true)
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webview

        // --- Configurações essenciais ---
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // necessário para localStorage
            allowFileAccess = true
            setSupportMultipleWindows(true)   // necessário para o window.open() do PDF
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            // Seletor de arquivos para <input type="file"> (restaurar backup)
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch("*/*")
                    true
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun getCantosList(): String {
            return try {
                val assets = activity.assets.list("cantos") ?: emptyArray()
                // Retorna apenas nomes de arquivos .txt sem a extensão
                assets.filter { it.endsWith(".txt") }
                    .map { it.removeSuffix(".txt") }
                    .joinToString("|")
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun getCantoContent(name: String): String {
            return try {
                activity.assets.open("cantos/$name.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "Erro ao carregar canto: $name"
            }
        }

        // Envio direto (SMTP do Gmail, sem abrir app de e-mail). pdfJson vazio = sem anexo.
        @JavascriptInterface
        fun enviarEmail(usuario: String, senha: String, para: String, assunto: String, texto: String, pdfJson: String, nomePdf: String) {
            Thread {
                var msg = "ok"
                try {
                    val pdf = if (pdfJson.isBlank()) null else EmailSender.gerarPdf(pdfJson)
                    EmailSender.enviar(usuario, senha, para, assunto, texto, pdf, nomePdf)
                } catch (e: Exception) {
                    msg = e.message ?: e.javaClass.simpleName
                }
                val js = "window.emailResultado && emailResultado(${org.json.JSONObject.quote(msg)})"
                activity.runOnUiThread { activity.binding.webview.evaluateJavascript(js, null) }
            }.start()
        }

        @JavascriptInterface
        fun copyText(text: String) {
            activity.runOnUiThread {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Comunidade 11", text))
                android.widget.Toast.makeText(activity, "Copiado! Cole no WhatsApp.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveBackup(json: String, fileName: String) {
            activity.runOnUiThread {
                try {
                    val dir = File(activity.cacheDir, "backup")
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    // Nome vindo do JS: mantém só o nome do arquivo, sem caminhos
                    val safeName = File(fileName).name.ifBlank { "backup_comunidade11.json" }
                    val file = File(dir, safeName)
                    file.writeText(json, Charsets.UTF_8)

                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "Salvar backup"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(activity, "Erro ao gerar backup: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun print(html: String) {
            activity.runOnUiThread {
                activity.printWebView = WebView(activity)
                activity.printWebView?.settings?.javaScriptEnabled = true
                activity.printWebView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        activity.imprimirWebView(view!!)
                    }
                }
                activity.printWebView?.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        }

        @JavascriptInterface
        fun shareAsImage(html: String) {
            activity.runOnUiThread {
                try {
                    android.widget.Toast.makeText(activity, "Gerando imagem, aguarde...", android.widget.Toast.LENGTH_SHORT).show()
                    
                    val container = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    val webView = WebView(activity)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        offscreenPreRaster = true
                    }
                    
                    // Adiciona ao layout mas invisível para ganhar contexto de renderização real
                    webView.visibility = android.view.View.INVISIBLE
                    container.addView(webView, android.view.ViewGroup.LayoutParams(1080, 500))

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.postDelayed({
                                try {
                                    val metrics = activity.resources.displayMetrics
                                    val width = 1080 // Largura ideal para WhatsApp
                                    
                                    view.measure(
                                        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                                    )
                                    
                                    val height = Math.min(view.measuredHeight, 8000) // Limite de 8000px para evitar erro de memória
                                    if (height <= 50) {
                                        android.widget.Toast.makeText(activity, "Falha ao medir o calendário", android.widget.Toast.LENGTH_SHORT).show()
                                        container.removeView(webView)
                                        return@postDelayed
                                    }

                                    view.layout(0, 0, width, height)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                view.draw(canvas)

                                val cachePath = File(activity.cacheDir, "shared_agenda")
                                cachePath.mkdirs()
                                cachePath.listFiles()?.forEach { it.delete() }
                                
                                val file = File(cachePath, "agenda_${System.currentTimeMillis()}.jpg")
                                val stream = FileOutputStream(file)
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                stream.close()

                                val contentUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                                
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, contentUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                
                                activity.startActivity(Intent.createChooser(intent, "Compartilhar Agenda"))
                                    container.removeView(webView)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    android.widget.Toast.makeText(activity, "Erro: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                    container.removeView(webView)
                                }
                            }, 2500)
                        }
                    }
                    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(activity, "Erro ao iniciar: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun addToCalendar(title: String, description: String, dateStr: String) {
            try {
                val parts = dateStr.split("-")
                val year = parts[0].toInt()
                val month = parts[1].toInt() - 1 // Calendar months are 0-based
                val day = parts[2].toInt()

                val startMillis: Long = java.util.Calendar.getInstance().apply {
                    set(year, month, day, 19, 0) // Default to 19:00 (7 PM)
                }.timeInMillis

                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
                    .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, title)
                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
                    .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, "Comunidade 11")
                    .putExtra(android.provider.CalendarContract.Events.AVAILABILITY, android.provider.CalendarContract.Events.AVAILABILITY_BUSY)

                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            val builder = androidx.core.app.NotificationCompat.Builder(activity, "COMUNIDADE_11")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .setAutoCancel(true)

            val notificationManager = androidx.core.app.NotificationManagerCompat.from(activity)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        activity,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
                }
            } else {
                notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
            }
        }
    }

    // Abre a tela de impressão do Android (o usuário pode escolher "Salvar como PDF")
    private fun imprimirWebView(webViewParaImprimir: WebView) {
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val jobName = "Comunidade_11"
        val printAdapter = webViewParaImprimir.createPrintDocumentAdapter(jobName)
        printManager.print(
            jobName,
            printAdapter,
            PrintAttributes.Builder().build()
        )
    }
}
