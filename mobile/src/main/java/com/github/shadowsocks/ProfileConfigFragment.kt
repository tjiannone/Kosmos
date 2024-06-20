package com.github.shadowsocks

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.MenuItem
import android.view.View
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.preference.*
import com.github.shadowsocks.api.ApiService
import com.github.shadowsocks.api.ClientConfig
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.plugin.*
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.github.shadowsocks.plugin.fragment.Empty
import com.github.shadowsocks.plugin.fragment.showAllowingStateLoss
import com.github.shadowsocks.preference.*
import com.github.shadowsocks.utils.*
import com.github.shadowsocks.widget.ListListener
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

class ProfileConfigFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener, OnPreferenceDataStoreChangeListener {
    companion object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {
        override fun provideSummary(preference: EditTextPreference) = "\u2022".repeat(preference.text?.length ?: 0)
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                ProfileManager.delProfile(arg.profileId)
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    private var profileId = -1L
    private lateinit var isProxyApps: SwitchPreference
    private lateinit var plugin: PluginPreference
    private lateinit var pluginConfigure: EditTextPreference
    private lateinit var pluginConfiguration: PluginConfiguration
    private lateinit var receiver: BroadcastReceiver
    private lateinit var udpFallback: Preference

    // Retrofit instance with logging interceptor
    private val retrofit: Retrofit by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://nip1ua8u78.execute-api.ap-southeast-1.amazonaws.com/test/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    private fun makeDirt() {
        DataStore.dirty = true
        (activity as ProfileConfigActivity).unsavedChangesHandler.isEnabled = true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.privateStore
        val activity = requireActivity()
        profileId = activity.intent.getLongExtra(Action.EXTRA_PROFILE_ID, -1L)
        if (profileId != -1L && profileId != DataStore.editingId) {
            activity.finish()
            return
        }
        addPreferencesFromResource(R.xml.pref_profile)
        findPreference<EditTextPreference>(Key.remotePort)!!.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        findPreference<EditTextPreference>(Key.password)!!.summaryProvider = PasswordSummaryProvider
        val serviceMode = DataStore.serviceMode
        findPreference<Preference>(Key.ipv6)!!.isEnabled = serviceMode == Key.modeVpn
        isProxyApps = findPreference(Key.proxyApps)!!
        isProxyApps.isEnabled = serviceMode == Key.modeVpn
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManager::class.java))
            if (newValue as Boolean) makeDirt()
            newValue
        }
        findPreference<Preference>(Key.metered)!!.apply {
            if (Build.VERSION.SDK_INT >= 28) isEnabled = serviceMode == Key.modeVpn else remove()
        }
        plugin = findPreference(Key.plugin)!!
        pluginConfigure = findPreference(Key.pluginConfigure)!!
        pluginConfigure.setOnBindEditTextListener(EditTextPreferenceModifiers.Monospace)
        pluginConfigure.onPreferenceChangeListener = this
        pluginConfiguration = PluginConfiguration(DataStore.plugin)
        initPlugins()
        udpFallback = findPreference(Key.udpFallback)!!
        DataStore.privateStore.registerChangeListener(this)

        val profile = ProfileManager.getProfile(profileId) ?: Profile()
        if (profile.subscription == Profile.SubscriptionStatus.Active) {
            findPreference<Preference>(Key.name)!!.isEnabled = false
            findPreference<Preference>(Key.host)!!.isEnabled = false
            findPreference<Preference>(Key.password)!!.isEnabled = false
            findPreference<Preference>(Key.method)!!.isEnabled = false
            findPreference<Preference>(Key.remotePort)!!.isEnabled = false
            plugin.isEnabled = false
            pluginConfigure.isEnabled = false
            udpFallback.isEnabled = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        setFragmentResultListener(PluginPreferenceDialogFragment::class.java.name) { _, bundle ->
            val selected = plugin.plugins.lookup.getValue(
                bundle.getString(PluginPreferenceDialogFragment.KEY_SELECTED_ID)!!)
            val override = pluginConfiguration.pluginsOptions.keys.firstOrNull {
                plugin.plugins.lookup[it] == selected
            }
            pluginConfiguration = PluginConfiguration(pluginConfiguration.pluginsOptions, override ?: selected.id)
            DataStore.plugin = pluginConfiguration.toString()
            makeDirt()
            plugin.value = pluginConfiguration.selected
            pluginConfigure.isEnabled = selected !is NoPlugin
            pluginConfigure.text = pluginConfiguration.getOptions().toString()
            if (!selected.trusted) {
                Snackbar.make(requireView(), R.string.plugin_untrusted, Snackbar.LENGTH_LONG).show()
            }
        }
        AlertDialogFragment.setResultListener<ProfileConfigActivity.UnsavedChangesDialogFragment, Empty>(this) {
                which, _ ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> saveAndExit()
                DialogInterface.BUTTON_NEGATIVE -> requireActivity().finish()
            }
        }
    }

    private fun initPlugins() {
        plugin.value = pluginConfiguration.selected
        plugin.init()
        pluginConfigure.isEnabled = plugin.selectedEntry?.let { it is NoPlugin } == false
        pluginConfigure.text = pluginConfiguration.getOptions().toString()
    }

    private fun showPluginEditor() {
        PluginConfigurationDialogFragment().apply {
            setArg(Key.pluginConfigure, pluginConfiguration.selected)
            setTargetFragment(this@ProfileConfigFragment, 0)
        }.showAllowingStateLoss(parentFragmentManager, Key.pluginConfigure)
    }

    private fun saveAndExit() {
        val profile = ProfileManager.getProfile(profileId) ?: Profile()
        profile.id = profileId
        profile.deserialize()
        ProfileManager.updateProfile(profile)
        ProfilesFragment.instance?.profilesAdapter?.deepRefreshId(profileId)
        if (profileId in Core.activeProfileIds && DataStore.directBootAware) DirectBoot.update()
        requireActivity().finish()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        receiver = context.listenForPackageChanges(false) {
            lifecycleScope.launch(Dispatchers.Main) {   // wait until changes were flushed
                whenCreated { initPlugins() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isProxyApps.isChecked = DataStore.proxyApps // fetch proxyApps updated by AppManager
        val fallbackProfile = DataStore.udpFallback?.let { ProfileManager.getProfile(it) }
        if (fallbackProfile == null) udpFallback.setSummary(R.string.plugin_disabled)
        else udpFallback.summary = fallbackProfile.formattedName
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean = try {
        val selected = pluginConfiguration.selected
        pluginConfiguration = PluginConfiguration(pluginConfiguration.pluginsOptions +
                (pluginConfiguration.selected to PluginOptions(selected, newValue as? String?)), selected)
        DataStore.plugin = pluginConfiguration.toString()
        makeDirt()
        true
    } catch (exc: RuntimeException) {
        Snackbar.make(requireView(), exc.readableMessage, Snackbar.LENGTH_LONG).show()
        false
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.proxyApps && findPreference<Preference>(key) != null) makeDirt()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            Key.plugin -> PluginPreferenceDialogFragment().apply {
                setArg(Key.plugin)
                setTargetFragment(this@ProfileConfigFragment, 0)
            }.showAllowingStateLoss(parentFragmentManager, Key.plugin)
            Key.pluginConfigure -> {
                val intent = PluginManager.buildIntent(plugin.selectedEntry!!.id, PluginContract.ACTION_CONFIGURE)
                if (intent.resolveActivity(requireContext().packageManager) == null) showPluginEditor() else {
                    configurePlugin.launch(intent
                        .putExtra(PluginContract.EXTRA_OPTIONS, pluginConfiguration.getOptions().toString()))
                }
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    private val configurePlugin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            (resultCode, data) ->
        when (resultCode) {
            Activity.RESULT_OK -> {
                val options = data?.getStringExtra(PluginContract.EXTRA_OPTIONS)
                pluginConfigure.text = options
                onPreferenceChange(pluginConfigure, options)
            }
            PluginContract.RESULT_FALLBACK -> showPluginEditor()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_delete -> {
            DeleteConfirmationDialogFragment().apply {
                arg(ProfileIdArg(profileId))
                key()
            }.show(parentFragmentManager, null)
            true
        }
        R.id.action_apply -> {
            saveAndExit()
            true
        }
        else -> false
    }

    private fun fetchConfig() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.getClientConfig().execute()
                }
                if (response.isSuccessful) {
                    val config = response.body()
                    if (config != null) {
                        saveConfigLocally(config)
                        configureProfile(config)
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

    private fun saveConfigLocally(config: ClientConfig) {
        // Implement your method to save the configuration locally
    }

    private fun configureProfile(config: ClientConfig) {
        // Implement your method to configure the profile with the fetched configuration
    }

    override fun onDetach() {
        requireContext().unregisterReceiver(receiver)
        super.onDetach()
    }

    override fun onDestroy() {
        DataStore.privateStore.unregisterChangeListener(this)
        super.onDestroy()
    }
}
