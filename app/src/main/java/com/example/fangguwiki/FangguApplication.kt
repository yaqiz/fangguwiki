package com.example.fangguwiki

import android.app.Application
import com.amap.api.maps.MapsInitializer

class FangguApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅仅做最基础的隐私初始化
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
    }
}
