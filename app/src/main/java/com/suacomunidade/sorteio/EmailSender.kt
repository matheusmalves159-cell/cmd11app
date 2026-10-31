package com.suacomunidade.sorteio

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Envia e-mail direto pelo SMTP do Gmail (porta 465/SSL) e gera o PDF dos grupos, sem bibliotecas externas. */
object EmailSender {

    private const val HOST = "smtp.gmail.com"
    private const val PORT = 465
    private const val CRLF = "\r\n"

    fun gerarPdf(json: String): ByteArray {
        val o = JSONObject(json)
        val doc = PdfDocument()
        val largura = 595
        val altura = 842
        val margem = 48f
        val marrom = Color.parseColor("#8C6B45")

        val titulo = Paint().apply { color = marrom; textSize = 22f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); isAntiAlias = true }
        val sub = Paint().apply { color = Color.GRAY; textSize = 12f; isAntiAlias = true }
        val grupo = Paint().apply { color = marrom; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val nome = Paint().apply { color = Color.rgb(32, 33, 36); textSize = 13f; isAntiAlias = true }
        val linha = Paint().apply { color = Color.rgb(232, 234, 237); strokeWidth = 1f }

        var pagina = 1
        var p = doc.startPage(PdfDocument.PageInfo.Builder(largura, altura, pagina).create())
        var c = p.canvas
        var y = margem + 20f
        c.drawText(o.optString("titulo"), margem, y, titulo); y += 20f
        c.drawText(o.optString("sub"), margem, y, sub); y += 30f

        fun novaPagina() {
            doc.finishPage(p)
            pagina++
            p = doc.startPage(PdfDocument.PageInfo.Builder(largura, altura, pagina).create())
            c = p.canvas
            y = margem + 10f
        }

        val grupos = o.optJSONArray("grupos")
        if (grupos != null) for (i in 0 until grupos.length()) {
            val g = grupos.getJSONObject(i)
            val nomes = g.getJSONArray("nomes")
            if (y + 48f > altura - margem) novaPagina()
            c.drawText(g.optString("nome"), margem, y, grupo); y += 8f
            c.drawLine(margem, y, largura - margem, y, linha); y += 18f
            for (j in 0 until nomes.length()) {
                if (y > altura - margem) novaPagina()
                c.drawText(nomes.getString(j), margem + 8f, y, nome); y += 20f
            }
            y += 14f
        }
        doc.finishPage(p)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun assuntoMime(s: String) = "=?UTF-8?B?" + b64(s) + "?="

    fun enviar(usuario: String, senha: String, para: String, assunto: String, texto: String, pdf: ByteArray?, nomePdf: String) {
        val sock = SSLSocketFactory.getDefault().createSocket(HOST, PORT) as SSLSocket
        sock.soTimeout = 25000
        sock.use {
            val rd = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val wr = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)

            fun resposta(): String {
                var ultima: String
                do {
                    ultima = rd.readLine() ?: throw Exception("Conexão encerrada pelo servidor")
                } while (ultima.length > 3 && ultima[3] == '-')
                return ultima
            }
            fun espera(codigo: String, falha: String) {
                val r = resposta()
                if (!r.startsWith(codigo)) throw Exception("$falha ($r)")
            }
            fun envia(l: String) { wr.write(l + CRLF); wr.flush() }

            espera("220", "Servidor não respondeu")
            envia("EHLO comunidade11"); espera("250", "Servidor recusou a conexão")
            envia("AUTH LOGIN"); espera("334", "Login não aceito")
            envia(b64(usuario)); espera("334", "E-mail de login recusado")
            envia(b64(senha.replace(" ", ""))); espera("235", "Senha de app incorreta")
            envia("MAIL FROM:<$usuario>"); espera("250", "Remetente recusado")
            envia("RCPT TO:<$para>"); espera("250", "Destinatário recusado")
            envia("DATA"); espera("354", "Servidor não aceitou o envio")

            val limite = "=_c11_" + System.currentTimeMillis()
            val data = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("From: $usuario").append(CRLF)
            sb.append("To: $para").append(CRLF)
            sb.append("Subject: ${assuntoMime(assunto)}").append(CRLF)
            sb.append("Date: $data").append(CRLF)
            sb.append("MIME-Version: 1.0").append(CRLF)
            sb.append("Content-Type: multipart/mixed; boundary=\"$limite\"").append(CRLF).append(CRLF)

            sb.append("--$limite").append(CRLF)
            sb.append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
            sb.append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF)
            sb.append(Base64.encodeToString(texto.toByteArray(Charsets.UTF_8), Base64.CRLF)).append(CRLF)

            if (pdf != null) {
                sb.append("--$limite").append(CRLF)
                sb.append("Content-Type: application/pdf; name=\"$nomePdf\"").append(CRLF)
                sb.append("Content-Transfer-Encoding: base64").append(CRLF)
                sb.append("Content-Disposition: attachment; filename=\"$nomePdf\"").append(CRLF).append(CRLF)
                sb.append(Base64.encodeToString(pdf, Base64.CRLF)).append(CRLF)
            }
            sb.append("--$limite--").append(CRLF)
            sb.append(".").append(CRLF)

            wr.write(sb.toString()); wr.flush()
            espera("250", "Não foi possível enviar")
            envia("QUIT")
        }
    }
}
