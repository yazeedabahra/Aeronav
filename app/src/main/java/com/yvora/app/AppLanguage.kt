package com.yvora.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class YvoraApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.load(this)
    }
}
internal object AppLanguage {
    @Volatile var arabic=false
        private set
    fun load(context:Context) { arabic=context.getSharedPreferences("app_language",Context.MODE_PRIVATE).getBoolean("arabic",false) }
    fun select(context:Context,value:Boolean):Boolean {
        if(arabic==value) return false
        context.getSharedPreferences("app_language",Context.MODE_PRIVATE).edit().putBoolean("arabic",value).apply()
        arabic=value
        return true
    }
    fun wrap(context:Context):Context {
        val locale=Locale.forLanguageTag(if(arabic) "ar" else "en")
        val config=Configuration(context.resources.configuration)
        config.setLocale(locale);config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
internal fun tr(en:String,ar:String):String=if(AppLanguage.arabic) ar else en
abstract class LocalizedActivity:AppCompatActivity() {
    override fun attachBaseContext(newBase:Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
}
