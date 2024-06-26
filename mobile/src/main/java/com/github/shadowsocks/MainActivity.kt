package com.github.shadowsocks

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.*
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.api.ApiService
import com.github.shadowsocks.api.ClientConfig
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.preference.OnPreferenceDataStoreChangeListener
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.StartService
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.ServiceButton
import com.github.shadowsocks.widget.StatsBar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

class MainActivity : AppCompatActivity(), ShadowsocksConnection.Callback, SharedPreferences.OnSharedPreferenceChangeListener,
    NavigationView.OnNavigationItemSelectedListener, OnPreferenceDataStoreChangeListener {

    companion object {
        var stateListener: ((BaseService.State) -> Unit)? = null
    }

    // UI
    private lateinit var fab: ServiceButton
    private lateinit var stats: StatsBar
    internal lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView

    lateinit var snackbar: CoordinatorLayout private set
    fun snackbar(text: CharSequence = "") = Snackbar.make(snackbar, text, Snackbar.LENGTH_LONG).apply {
        anchorView = fab
    }

    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.light_color_primary))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.dark_color_primary))
            }.build())
        }.build()
    }

    fun launchUrl(uri: String) = try {
        customTabsIntent.launchUrl(this, uri.toUri())
    } catch (_: ActivityNotFoundException) {
        snackbar(uri).show()
    }

    // Retrofit instance with logging interceptor
    private val retrofit: Retrofit by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(BasicAuthInterceptor("admin", "password")) // Add your interceptor here
            .build()

        Retrofit.Builder()
            .baseUrl("https://3q8366ztj4.execute-api.us-east-1.amazonaws.com/test/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    // Service
    var state = BaseService.State.Idle

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        changeState(state, msg)

    override fun onServiceConnected(service: IShadowsocksService) = changeState(try {
        BaseService.State.values()[service.state]
    } catch (_: RemoteException) {
        BaseService.State.Idle
    })

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId == 0L) this@MainActivity.stats.updateTraffic(
            stats.txRate, stats.rxRate, stats.txTotal, stats.rxTotal)
        if (state != BaseService.State.Stopping) {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ProfilesFragment)
                ?.onTrafficUpdated(profileId, stats)
        }
    }

    override fun trafficPersisted(profileId: Long) {
        ProfilesFragment.instance?.onTrafficPersisted(profileId)
    }

    @SuppressLint("StringFormatInvalid")
    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = true) {
        fab.changeState(state, this.state, animate)
        stats.changeState(state, animate)
        if (msg != null) snackbar(getString(R.string.proxy_error, msg)).show()
        this.state = state
        ProfilesFragment.instance?.profilesAdapter?.notifyDataSetChanged()  // refresh button enabled state
        stateListener?.invoke(state)
    }

    private fun setupCloakConfig() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.getClientConfig().execute()
                }
                if (response.isSuccessful) {
                    val config = response.body()
                    if (config != null) {
                        val currentProfile = findMatchingProfile(config)
                        if (currentProfile != null) {
                            // Use the existing matching profile
                            Core.switchProfile(currentProfile.id)
                            Core.startService()
                            changeState(BaseService.State.Connecting)
                        } else {
                            // Create a new profile
                            configureProfile(config)
                        }
                    } else {
                        Timber.e("Failed to get Cloak config: response body is null")
                    }
                } else {
                    Timber.e("Failed to get Cloak config: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error getting Cloak config")
            }
        }
    }

    private fun configureProfile(config: ClientConfig) {
        val newProfile = Profile().apply {
            name = config.serverName
            host = config.publicIP
            remotePort = config.port?.toInt() ?: 443
            password = config.password ?: ""
            method = config.encryption ?: "aes-256-gcm"
            plugin = "ck-client;UID=${config.uid};PublicKey=${config.publicKey};ServerName=${config.serverName};CloakProxyMethod=${config.cloakProxyMethod};ProxyMethod=${config.proxyMethod};EncryptionMethod=${config.encryptionMethod};NumConn=${config.numConn};BrowserSig=${config.browserSig};StreamTimeout=${config.streamTimeout}"
        }
        ProfileManager.createProfile(newProfile)
        Core.switchProfile(newProfile.id)
        Core.startService()
        changeState(BaseService.State.Connecting)
    }

    private fun findMatchingProfile(config: ClientConfig): Profile? {
        val allProfiles = ProfileManager.getAllProfiles() ?: return null
        return allProfiles.firstOrNull { profile ->
            profile.host == config.publicIP &&
                    profile.remotePort.toString() == config.port &&
                    profile.password == config.password &&
                    profile.method == config.encryption &&
                    profile.plugin == "ck-client;UID=${config.uid};PublicKey=${config.publicKey};ServerName=${config.serverName};CloakProxyMethod=${config.cloakProxyMethod};ProxyMethod=${config.proxyMethod};EncryptionMethod=${config.encryptionMethod};NumConn=${config.numConn};BrowserSig=${config.browserSig};StreamTimeout=${config.streamTimeout}"
        }
    }

    private val connection = ShadowsocksConnection(true)

    private val connect = registerForActivityResult(StartService()) {
        if (it) snackbar().setText(R.string.proxy_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.layout_main)
        snackbar = findViewById(R.id.snackbar)
        ViewCompat.setOnApplyWindowInsetsListener(snackbar, ListHolderListener)
        stats = findViewById(R.id.stats)
        stats.setOnClickListener { if (state == BaseService.State.Connected) stats.testConnection() }
        drawer = findViewById(R.id.drawer)
        val drawerHandler = object : OnBackPressedCallback(drawer.isOpen), DrawerLayout.DrawerListener {
            override fun handleOnBackPressed() = drawer.closeDrawers()
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) { }
            override fun onDrawerOpened(drawerView: View) {
                isEnabled = true
            }
            override fun onDrawerClosed(drawerView: View) {
                isEnabled = false
            }
            override fun onDrawerStateChanged(newState: Int) {
                isEnabled = newState == DrawerLayout.STATE_IDLE == drawer.isOpen
            }
        }
        onBackPressedDispatcher.addCallback(drawerHandler)
        drawer.addDrawerListener(drawerHandler)
        navigation = findViewById(R.id.navigation)
        navigation.setNavigationItemSelectedListener(this)
        if (savedInstanceState == null) {
            navigation.menu.findItem(R.id.profiles).isChecked = true
            displayFragment(ProfilesFragment())
        }

        fab = findViewById(R.id.fab)
        fab.initProgress(findViewById(R.id.fabProgress))
        fab.setOnClickListener {
            if (state == BaseService.State.Connected) {
                Core.stopService()
                changeState(BaseService.State.Stopping)
            } else {
                setupCloakConfig()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom +
                        resources.getDimensionPixelOffset(R.dimen.mtrl_bottomappbar_fab_bottom_margin)
            }
            insets
        }

        changeState(BaseService.State.Idle, animate = false)    // reset everything to init state
        connection.connect(this, this)
        DataStore.publicStore.registerChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (key == Key.serviceMode) {
            connection.disconnect(this)
            connection.connect(this, this)
        }
    }

    private fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        drawer.closeDrawers()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) drawer.closeDrawers() else {
            when (item.itemId) {
                R.id.profiles -> {
                    displayFragment(ProfilesFragment())
                    connection.bandwidthTimeout = connection.bandwidthTimeout   // request stats update
                }

                R.id.about -> {
                    Firebase.analytics.logEvent("about") { }
                    displayFragment(AboutFragment())
                }
                R.id.faq -> {
                    val faqUrl = getString(R.string.faq_url)
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(faqUrl))
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        snackbar(faqUrl).show()
                    }
                    return true
                }
                else -> return false
            }
            item.isChecked = true
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 500
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent) = when {
        keyCode == KeyEvent.KEYCODE_G && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            setupCloakConfig()
            true
        }
        keyCode == KeyEvent.KEYCODE_T && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            stats.testConnection()
            true
        }
        else -> (supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment).toolbar.menu.let {
            it.setQwertyMode(KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC)
            it.performShortcut(keyCode, event, 0)
        }
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.publicStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        // Handle preference data store changes if needed
    }
}
