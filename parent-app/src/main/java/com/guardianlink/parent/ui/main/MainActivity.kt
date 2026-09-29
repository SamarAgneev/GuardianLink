// parent-app/src/main/java/com/guardianlink/parent/ui/main/MainActivity.kt
package com.guardianlink.parent.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.auth.AuthActivity
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val dashboardViewModel: DashboardViewModel by viewModels()

    private lateinit var navController: NavController
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        setupNavigation()
        observeAlertBadge()
        selectFirstDevice()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(this, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        bottomNav = findViewById(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // Hide bottom nav on certain destinations
        navController.addOnDestinationChangedListener { _, destination, _ ->
            bottomNav.visibility = when (destination.id) {
                R.id.liveViewFragment -> View.GONE
                else                  -> View.VISIBLE
            }
        }
    }

    private fun observeAlertBadge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dashboardViewModel.unreadAlertCount.collect { count ->
                    val badge: BadgeDrawable = bottomNav.getOrCreateBadge(R.id.alertsFragment)
                    if (count > 0) {
                        badge.isVisible = true
                        badge.number   = count
                    } else {
                        badge.isVisible = false
                    }
                }
            }
        }
    }

    private fun selectFirstDevice() {
        lifecycleScope.launch {
            dashboardViewModel.childDevices.collect { devices ->
                if (devices.isNotEmpty() &&
                    dashboardViewModel.selectedDeviceId.value.isBlank()) {
                    dashboardViewModel.selectDevice(devices.first().deviceId)
                }
            }
        }
    }
}
