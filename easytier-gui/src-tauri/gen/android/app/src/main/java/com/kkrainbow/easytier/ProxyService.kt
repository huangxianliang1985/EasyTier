package com.kkranbow.easytier
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {
    private val tag = "ProxyService"
    private lateinit var threadPool: ExecutorService
    private lateinit var serverSocket: ServerSocket
    private var isRunning = false

    // 核心启动逻辑
    override fun onCreate() {
        super.onCreate()
        startProxyServer()
        showForegroundNotification()
    }
    // 修改 onStartCommand
    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 添加保活机制
        return START_STICKY_COMPATIBILITY or START_REDELIVER_INTENT
    }

    private fun startProxyServer() {
        threadPool = Executors.newCachedThreadPool()
        isRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(11112).apply {
                    reuseAddress = true  // 添加端口重用
                }
                Log.d(tag, "代理服务启动在 0.0.0.0:11112")

                while (isRunning) {
                    val clientSocket = serverSocket.accept()
                    threadPool.execute { handleClientRequest(clientSocket) }
                }
            } catch (e: IOException) {
                if (isRunning) Log.e(tag, "服务异常终止", e)
            }
        }.start()
    }

    private fun handleClientRequest(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return

                when {
                    requestLine.startsWith("CONNECT") -> processHttpsRequest(socket, requestLine)
                    else -> processHttpRequest(socket, requestLine, reader)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "请求处理异常", e)
        }
    }

    private fun processHttpsRequest(clientSocket: Socket, requestLine: String) {
        val (_, hostPort) = requestLine.split(" ", limit = 3)
        val (host, port) = hostPort.split(":").let {
            it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 443)
        }

        try {
            Socket(host, port).use { targetSocket ->
                // 确认隧道建立
                clientSocket.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    flush()
                }

                // 启动双向转发
                val clientToTarget = Thread {
                    forwardStream(clientSocket.getInputStream(), targetSocket.getOutputStream())
                }
                val targetToClient = Thread {
                    forwardStream(targetSocket.getInputStream(), clientSocket.getOutputStream())
                }

                clientToTarget.start()
                targetToClient.start()
                clientToTarget.join()
                targetToClient.join()
            }
        } catch (e: Exception) {
            Log.e(tag, "HTTPS代理失败: $host:$port", e)
        }
    }

    private fun processHttpRequest(
        clientSocket: Socket,
        requestLine: String,
        reader: BufferedReader
    ) {
        val (method, url) = requestLine.split(" ", limit = 3).let { it[0] to it[1] }

        if (!url.contains("/api/")) {
            clientSocket.getOutputStream().writeForbiddenResponse()
            return
        }

        try {
            URL(url).run {
                val targetHost = host
                val targetPort = port.takeIf { it != -1 } ?: defaultPort

                Socket(targetHost, targetPort).use { targetSocket ->
                    // 转发请求头
                    val headers = buildString {
                        appendLine("$method $path HTTP/1.1")
                        appendLine("Host: $targetHost")
                        appendRequestHeaders(reader)
                    }

                    targetSocket.getOutputStream().apply {
                        write(headers.toByteArray())
                        flush()
                    }

                    // 转发响应
                    forwardStream(targetSocket.getInputStream(), clientSocket.getOutputStream())
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "HTTP代理失败: $url", e)
            clientSocket.getOutputStream().writeErrorResponse()
        }
    }

    // 以下是工具方法
    private fun OutputStream.writeForbiddenResponse() {
        write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
        flush()
    }

    private fun OutputStream.writeErrorResponse() {
        write("HTTP/1.1 500 Internal Server Error\r\n\r\n".toByteArray())
        flush()
    }

    private fun StringBuilder.appendRequestHeaders(reader: BufferedReader) {
        var line: String?
        while (reader.readLine().also { line = it } != null && line?.isNotEmpty() == true) {
            appendLine(line)
        }
        appendLine()  // 空行结束头
    }

    private fun forwardStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val bytesRead = input.read(buffer).takeIf { it >= 0 } ?: break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: IOException) {
            // 正常关闭时的异常可忽略
        }
    }

    // 前台通知配置
    private fun showForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                "proxy_channel",
                "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台代理服务运行中"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(this)
            }
        }

        val notification = NotificationCompat.Builder(this, "proxy_channel")
            .setContentTitle("网络代理服务")
            .setContentText("服务运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1201, notification)
    }

    override fun onDestroy() {
        isRunning = false
        threadPool.shutdownNow()
        try {
            serverSocket.close()
        } catch (e: IOException) {
            Log.w(tag, "关闭服务端套接字异常", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}