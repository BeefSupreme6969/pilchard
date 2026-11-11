package com.pilchard.player

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Pilchard's home screen: how to use it, plus the one-time Jellyfin sign-in. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var form: View
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var signOut: Button
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerAccount.init(this)
        setContentView(R.layout.activity_main)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        // Family copy says so, so it's easy to tell apart from the Play Store copy on the same Shield.
        val label = getString(R.string.version_label, version)
        findViewById<TextView>(R.id.version).text = if (BuildConfig.FOLLOW_DEVICE_LANGUAGE) label else "$label · Family"

        status = findViewById(R.id.account_status)
        form = findViewById(R.id.sign_in_form)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        signIn = findViewById(R.id.sign_in)
        signOut = findViewById(R.id.sign_out)
        message = findViewById(R.id.sign_in_message)

        server.setText(ServerAccount.get(this)?.baseUrl ?: ServerAccount.lastSeenServer(this).orEmpty())

        signIn.setOnClickListener {
            val address = server.text.toString()
            val user = username.text.toString().trim()
            if (address.isBlank() || user.isBlank()) {
                message.text = getString(R.string.server_address)
                return@setOnClickListener
            }
            message.setText(R.string.signing_in)
            signIn.isEnabled = false
            ServerAccount.signIn(this, address, user, password.text.toString()) { error ->
                signIn.isEnabled = true
                message.text = error ?: ""
                password.setText("")
                refresh()
            }
        }
        val dispatcharr = findViewById<EditText>(R.id.dispatcharr)
        dispatcharr.setText(DispatcharrPlaylist.url(this).orEmpty())
        findViewById<Button>(R.id.save_dispatcharr).setOnClickListener {
            DispatcharrPlaylist.save(this, dispatcharr.text.toString())
            dispatcharr.setText(DispatcharrPlaylist.url(this).orEmpty())
            message.setText(R.string.dispatcharr_saved)
        }
        signOut.setOnClickListener {
            ServerAccount.signOut(this)
            message.text = ""
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        val account = ServerAccount.get(this)
        if (account != null) {
            status.text = getString(R.string.signed_in_as, account.userName.ifBlank { "?" }) + "\n" + account.baseUrl
            form.visibility = View.GONE
            signOut.visibility = View.VISIBLE
            signOut.requestFocus()
        } else {
            status.setText(R.string.not_signed_in)
            form.visibility = View.VISIBLE
            signOut.visibility = View.GONE
            (if (server.text.isBlank()) server else username).requestFocus()
        }
    }
}
