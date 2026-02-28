package com.ypsopump.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ypsopump.test.key.SecureKeyStore
import com.ypsopump.test.ui.navigation.AppNavigation
import com.ypsopump.test.ui.theme.YpsoPumpTestTheme

class MainActivity : ComponentActivity() {

    private lateinit var keyStore: SecureKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyStore = SecureKeyStore(applicationContext)

        setContent {
            YpsoPumpTestTheme {
                AppNavigation(keyStore)
            }
        }
    }
}
