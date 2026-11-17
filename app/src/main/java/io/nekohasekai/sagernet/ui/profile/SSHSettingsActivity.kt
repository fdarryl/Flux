package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
    }

    override fun SSHBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_NONE -> {
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = DataStore.serverPassword
            }
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = DataStore.serverPrivateKey
                privateKeyPassphrase = DataStore.serverPassword1
            }
        }
        publicKey = DataStore.serverCertificates
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.ssh_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKey = findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKeyPassphrase = findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        fun updateAuthType(type: Int = DataStore.serverAuthType) {
            password.isVisible = type == SSHBean.AUTH_TYPE_PASSWORD
            privateKey.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
            privateKeyPassphrase.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
        }
        updateAuthType()
        authType.setOnPreferenceChangeListener { _, newValue ->
            updateAuthType((newValue as String).toInt())
            true
        }
    }

    override fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        if (preference.key == Key.SERVER_PRIVATE_KEY) {
            val ctx = context ?: return false
            val dialogView = layoutInflater.inflate(R.layout.dialog_ssh_key, null, false)
            val inputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.keyInputLayout)
            val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.keyInput)

            inputLayout.hint = getString(R.string.ssh_private_key)
            editText.setText(DataStore.serverPrivateKey)
            editText.inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT or
                android.view.inputmethod.EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                android.view.inputmethod.EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editText.isSingleLine = false
            editText.minLines = 4
            editText.maxLines = 10
            editText.gravity = android.view.Gravity.TOP or android.view.Gravity.START

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.ssh_private_key)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = editText.text?.toString()?.trim() ?: ""
                    (preference as? EditTextPreference)?.text = text
                    DataStore.serverPrivateKey = text
                    preference.callChangeListener(text)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return true
        }
        return false
    }

}