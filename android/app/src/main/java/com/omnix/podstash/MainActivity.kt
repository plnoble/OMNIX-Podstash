package com.omnix.podstash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.omnix.podstash.ui.PodstashRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            LaunchedEffect(Unit) {
                vm.loadTrending("cn")
                vm.refreshSubscriptionsOnOpen()
                vm.checkUpdate()
            }
            PodstashRoot(vm)
        }
    }
}
