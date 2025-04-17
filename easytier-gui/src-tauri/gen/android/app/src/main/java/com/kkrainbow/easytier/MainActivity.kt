package com.kkrainbow.easytier
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
class MainActivity : TauriActivity(){
  
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)


      // 启动服务
      Intent(this, ProxyService::class.java).apply {
        
          startForegroundService(this)
        
      }
    }
}