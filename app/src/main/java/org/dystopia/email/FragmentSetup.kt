/*
 * This file is part of FairEmail.
 *
 * FairEmail is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * FairEmail is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with FairEmail. If not,
 * see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2018, Marcel Bokhorst (M66B)
 * Copyright 2018-2023, Distopico (dystopia project) <distopico@riseup.net> and contributors
 */
package org.dystopia.email

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import org.dystopia.email.ServiceSynchronize.Companion.reload
import org.dystopia.email.databinding.FragmentSetupBinding
import org.dystopia.email.util.CompatibilityHelper.isIgnoringOptimizations
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class FragmentSetup : FragmentEx() {
    private var _fragmentSetupBinding: FragmentSetupBinding? = null
    private var check: Drawable? = null

    private val binding get() = _fragmentSetupBinding!!

    companion object {
        val EXPORT_SETTINGS: List<String> = mutableListOf(
            "enabled",
            "avatars",
            "light",
            "browse",
            "swipe",
            "compat",
            "insecure",
            "sort"
        )
        private fun getIntentExport(): Intent {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(
                Intent.EXTRA_TITLE,
                "simpleemail_backup_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date().time) + ".json"
            )
            return intent
        }

        private fun getIntentImport(): Intent {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            return intent
        }
    }

    private val intentHelp: Intent get() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(
            "https://framagit.org/dystopia-project/simple-email/blob/8f7296ddc2275471d4190df1dd55dee4025a5114/docs/SETUP.md"
        )
        return intent
    }

    private val permissions: Array<String> get() {
        val perm: MutableList<String> = ArrayList()
        perm.add(Manifest.permission.READ_CONTACTS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perm.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return perm.toTypedArray()
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setSubtitle(R.string.title_setup)
        setHasOptionsMenu(true)
        check = resources.getDrawable(R.drawable.baseline_check_24, requireContext().theme)
        _fragmentSetupBinding = FragmentSetupBinding.inflate(inflater, container, false)
        val view = binding.root

        // Wire controls
        binding.ibHelp.setOnClickListener {
            startActivity(intentHelp)
        }

        binding.btnAccount.setOnClickListener {
                val fragmentTransaction = requireFragmentManager().beginTransaction()
                fragmentTransaction
                    .replace(R.id.content_frame, FragmentAccounts())
                    .addToBackStack("accounts")
                fragmentTransaction.commit()
        }

        binding.btnIdentity.setOnClickListener {
                val fragmentTransaction = requireFragmentManager().beginTransaction()
                fragmentTransaction
                    .replace(R.id.content_frame, FragmentIdentities())
                    .addToBackStack("identities")
                fragmentTransaction.commit()
        }

        binding.btnPermissions.setOnClickListener {
                binding.btnPermissions.isEnabled = false
                requestPermissions(permissions, 1)
        }

        binding.btnDoze.setOnClickListener {
            DialogBuilderLifecycle(context, viewLifecycleOwner)
                .setMessage(R.string.title_setup_doze_instructions)
                .setPositiveButton(
                    android.R.string.ok
                ) { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    } catch (ex: Throwable) {
                        Log.e(
                            Helper.TAG, """$ex ${Log.getStackTraceString(ex)}""".trimIndent()
                        )
                    }
                }
                .create()
                .show()
        }

        binding.btnData.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID)
                    )
                )
            } catch (ex: Throwable) {
                Log.e(
                    Helper.TAG, """$ex ${Log.getStackTraceString(ex)}""".trimIndent()
                )
            }
        }

        val pm = requireContext().packageManager
        binding.btnNotifications.visibility = View.GONE
        if (getIntentNotifications().resolveActivity(pm) != null) {
            View.VISIBLE
        }
        binding.btnNotifications.setOnClickListener {
            startActivity(getIntentNotifications())
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = prefs.getString("theme", "light")
        val dark = "dark" == theme

        binding.tbDarkTheme.tag = dark
        binding.tbDarkTheme.isChecked = dark
        binding.tbDarkTheme.setOnCheckedChangeListener { button, checked ->
            if (checked != button.tag as Boolean) {
                button.tag = checked
                binding.tbDarkTheme.isChecked = checked
                prefs.edit().putString("theme", if (checked) "dark" else "light").apply()
            }
        }
        binding.btnOptions.setOnClickListener {
            val fragmentTransaction = requireFragmentManager().beginTransaction()
            fragmentTransaction
                .replace(R.id.content_frame, FragmentOptions())
                .addToBackStack("options")
            fragmentTransaction.commit()
        }

        // Initialize
        binding.ibHelp.visibility = View.GONE

        binding.tvAccountDone.text = null
        binding.tvAccountDone.setCompoundDrawables(null, null, null, null)

        binding.btnIdentity.isEnabled = false
        binding.tvIdentityDone.text = null
        binding.tvIdentityDone.setCompoundDrawables(null, null, null, null)

        binding.tvPermissionsDone.text = null
        binding.tvPermissionsDone.setCompoundDrawables(null, null, null, null)

        binding.btnDoze.isEnabled = false
        binding.tvDozeDone.text = null
        binding.tvDozeDone.setCompoundDrawables(null, null, null, null)

        binding.btnData.visibility = View.GONE

        val grantResults = IntArray(permissions.size)
        for (i in permissions.indices) {
            grantResults[i] = ContextCompat.checkSelfPermission(requireActivity(), permissions[i])
        }
        onRequestPermissionsResult(0, permissions, grantResults)

        // Create outbox
        object : SimpleTask<Unit>() {
            override fun onLoad(context: Context, args: Bundle) {
                val db = DB.getInstance(context)
                try {
                    db.beginTransaction()
                    var outbox = db.folder().outbox
                    if (outbox == null) {
                        outbox = EntityFolder()
                        outbox.name = "OUTBOX"
                        outbox.type = EntityFolder.OUTBOX
                        outbox.synchronize = false
                        outbox.after = 0
                        outbox.id = db.folder().insertFolder(outbox)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            override fun onException(args: Bundle, ex: Throwable) {
                Helper.unexpectedError(context, ex)
            }
        }.load(this, Bundle())
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val pm = requireContext().packageManager
        val db = DB.getInstance(context)

        binding.ibHelp.visibility =
            if (intentHelp.resolveActivity(pm) == null) View.GONE else View.VISIBLE

        db.account()
            .liveAccounts(true)
            .observe(
                viewLifecycleOwner
            ) { accounts ->
                val done = accounts != null && accounts.size > 0

                binding.btnIdentity.isEnabled = done
                binding.tvAccountDone.setText(
                    if (done) R.string.title_setup_done else R.string.title_setup_to_do
                )
                binding.tvAccountDone.setCompoundDrawablesWithIntrinsicBounds(
                    if (done) check else null, null, null, null
                )
            }
        db.identity()
            .liveIdentities(true)
            .observe(
                viewLifecycleOwner
            ) { identities ->
                val done = identities != null && identities.size > 0

                binding.tvIdentityDone.setText(
                    if (done) R.string.title_setup_done else R.string.title_setup_to_do
                )
                binding.tvIdentityDone.setCompoundDrawablesWithIntrinsicBounds(
                    if (done) check else null, null, null, null
                )
            }
    }

    override fun onResume() {
        super.onResume()
        var ignoring = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            val packageManager = requireContext().packageManager
            if (intent.resolveActivity(packageManager) != null) { // system whitelisted
                ignoring = isIgnoringOptimizations(requireContext())
            }
        }
        binding.btnDoze.isEnabled = !ignoring
        binding.tvDozeDone.setText(if (ignoring) R.string.title_setup_done else R.string.title_setup_to_do)
        binding.tvDozeDone.setCompoundDrawablesWithIntrinsicBounds(
            if (ignoring) check else null,
            null,
            null,
            null
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = requireContext().getSystemService(
                ConnectivityManager::class.java
            )
            val saving = (cm.restrictBackgroundStatus
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

            binding.btnData.visibility = if (saving) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_setup, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val pm = requireContext().packageManager
        menu.findItem(R.id.menu_export).isEnabled =
            getIntentExport().resolveActivity(pm) != null
        menu.findItem(R.id.menu_import).isEnabled = getIntentExport().resolveActivity(pm) != null
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_legend -> {
                onMenuLegend()
                true
            }

            R.id.menu_export -> {
                onMenuExport()
                true
            }

            R.id.menu_import -> {
                onMenuImport()
                true
            }

            R.id.menu_privacy -> {
                onMenuPrivacy()
                true
            }

            R.id.menu_about -> {
                onMenuAbout()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        var has = grantResults.isNotEmpty()
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                has = false
                break
            }
        }
        binding.btnPermissions.isEnabled = !has
        binding.tvPermissionsDone.setText(if (has) R.string.title_setup_done else R.string.title_setup_to_do)
        binding.tvPermissionsDone.setCompoundDrawablesWithIntrinsicBounds(
            if (has) check else null,
            null,
            null,
            null
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(Helper.TAG, "Request=$requestCode result=$resultCode data=$data")
        if (requestCode == ActivitySetup.REQUEST_EXPORT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                handleExport(data)
            }
        } else if (requestCode == ActivitySetup.REQUEST_IMPORT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                handleImport(data)
            }
        }
    }

    private fun onMenuPrivacy() {
        val fragmentTransaction = requireFragmentManager().beginTransaction()
        fragmentTransaction
            .replace(R.id.content_frame, FragmentPrivacy())
            .addToBackStack("privacy")
        fragmentTransaction.commit()
    }

    private fun onMenuLegend() {
        val fragmentTransaction = requireFragmentManager().beginTransaction()
        fragmentTransaction.replace(R.id.content_frame, FragmentLegend()).addToBackStack("legend")
        fragmentTransaction.commit()
    }

    private fun onMenuExport() {
        DialogBuilderLifecycle(context, viewLifecycleOwner)
            .setMessage(R.string.title_setup_export_do)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                try {
                    startActivityForResult(getIntentExport(), ActivitySetup.REQUEST_EXPORT)
                } catch (ex: Throwable) {
                    Log.e(
                        Helper.TAG, "$ex\n${Log.getStackTraceString(ex)}".trimIndent()
                    )
                }
            }
            .create()
            .show()
    }

    private fun onMenuImport() {
        DialogBuilderLifecycle(context, viewLifecycleOwner)
            .setMessage(R.string.title_setup_import_do)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                try {
                    startActivityForResult(getIntentImport(), ActivitySetup.REQUEST_IMPORT)
                } catch (ex: Throwable) {
                    Log.e(
                        Helper.TAG, "$ex\n${Log.getStackTraceString(ex)}".trimIndent()
                    )
                }
            }
            .create()
            .show()
    }

    private fun onMenuAbout() {
        val fragmentTransaction = requireFragmentManager().beginTransaction()
        fragmentTransaction.replace(R.id.content_frame, FragmentAbout()).addToBackStack("about")
        fragmentTransaction.commit()
    }

    private fun handleExport(data: Intent) {
        val args = Bundle()
        args.putParcelable("uri", data.data)

        object : SimpleTask<Unit?>() {
            @Throws(Throwable::class)
            override fun onLoad(context: Context, args: Bundle) {
                val uri = args.getParcelable<Uri>("uri")
                var out: OutputStream? = null

                try {
                    Log.i(Helper.TAG, "Writing URI=$uri")
                    out = requireContext().contentResolver.openOutputStream(uri!!)
                    val db = DB.getInstance(context)

                    // Accounts
                    val jaccounts = JSONArray()
                    for (account in db.account().accounts) {
                        // Account
                        val jaccount = account.toJSON()

                        // Identities
                        val jidentities = JSONArray()
                        for (identity in db.identity().getIdentities(account.id)) {
                            jidentities.put(identity.toJSON())
                        }
                        jaccount.put("identities", jidentities)

                        // Folders
                        val jfolders = JSONArray()
                        for (folder in db.folder().getFolders(account.id)) {
                            jfolders.put(folder.toJSON())
                        }
                        jaccount.put("folders", jfolders)
                        jaccounts.put(jaccount)
                    }

                    // Answers
                    val janswers = JSONArray()
                    for (answer in db.answer().answers) {
                        janswers.put(answer.toJSON())
                    }

                    // Settings
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    val jsettings = JSONArray()
                    for (key in prefs.all.keys) {
                        if (EXPORT_SETTINGS.contains(key)) {
                            val jsetting = JSONObject()
                            jsetting.put("key", key)
                            jsetting.put("value", prefs.all[key])
                            jsettings.put(jsetting)
                        }
                    }
                    val jexport = JSONObject()
                    jexport.put("accounts", jaccounts)
                    jexport.put("answers", janswers)
                    jexport.put("settings", jsettings)
                    out!!.write(jexport.toString(2).toByteArray())
                    Log.i(Helper.TAG, "Exported data")
                } finally {
                    out?.close()
                }
            }

            override fun onLoaded(args: Bundle?, data: Unit?) {
                Snackbar.make(view!!, R.string.title_setup_exported, Snackbar.LENGTH_LONG).show()
            }

            override fun onException(args: Bundle?, ex: Throwable) {
                Helper.unexpectedError(context, ex)
            }
        }.load(this, args)
    }

    private fun handleImport(data: Intent) {
        val args = Bundle()
        args.putParcelable("uri", data.data)

        object : SimpleTask<Unit>() {
            @Throws(Throwable::class)
            override fun onLoad(context: Context, args: Bundle) {
                val uri = args.getParcelable<Uri>("uri")
                var `in`: InputStream? = null

                try {
                    Log.i(Helper.TAG, "Reading URI=$uri")
                    val resolver = requireContext().contentResolver
                    val descriptor = resolver.openTypedAssetFileDescriptor(uri!!, "*/*", null)
                    `in` = descriptor!!.createInputStream()

                    val reader = BufferedReader(InputStreamReader(`in`))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    Log.i(Helper.TAG, "Importing $resolver")

                    val jimport = JSONObject(response.toString())
                    val db = DB.getInstance(context)

                    try {
                        db.beginTransaction()
                        val jaccounts = jimport.getJSONArray("accounts")

                        for (a in 0 until jaccounts.length()) {
                            val jaccount = jaccounts[a] as JSONObject
                            val account = EntityAccount.fromJSON(jaccount)

                            account.store_sent = false
                            account.id = db.account().insertAccount(account)
                            Log.i(Helper.TAG, "Imported account=" + account.name)

                            val jidentities = jaccount["identities"] as JSONArray
                            for (i in 0 until jidentities.length()) {
                                val jidentity = jidentities[i] as JSONObject
                                val identity = EntityIdentity.fromJSON(jidentity)
                                identity.account = account.id
                                identity.id = db.identity().insertIdentity(identity)
                                Log.i(Helper.TAG, "Imported identity=" + identity.email)
                            }

                            val jfolders = jaccount["folders"] as JSONArray
                            for (f in 0 until jfolders.length()) {
                                val jfolder = jfolders[f] as JSONObject
                                val folder = EntityFolder.fromJSON(jfolder)
                                folder.account = account.id
                                folder.id = db.folder().insertFolder(folder)
                                Log.i(Helper.TAG, "Imported folder=" + folder.name)
                            }
                        }

                        val janswers = jimport.getJSONArray("answers")
                        for (a in 0 until janswers.length()) {
                            val janswer = janswers[a] as JSONObject
                            val answer = EntityAnswer.fromJSON(janswer)
                            answer.id = db.answer().insertAnswer(answer)
                            Log.i(Helper.TAG, "Imported answer=" + answer.name)
                        }

                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val editor = prefs.edit()
                        val jsettings = jimport.getJSONArray("settings")
                        for (s in 0 until jsettings.length()) {
                            val jsetting = jsettings[s] as JSONObject
                            val key = jsetting.getString("key")
                            if (EXPORT_SETTINGS.contains(key)) {
                                val value = jsetting["value"]
                                if (value is Boolean) {
                                    editor.putBoolean(key, value)
                                } else if (value is String) {
                                    editor.putString(key, value)
                                } else {
                                    throw IllegalArgumentException("Unknown settings type key=$key")
                                }
                                Log.i(Helper.TAG, "Imported setting=$key")
                            }
                        }
                        editor.apply()
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    Log.i(Helper.TAG, "Imported data")
                } finally {
                    `in`?.close()
                }
            }

            override fun onLoaded(args: Bundle?, data: Unit?) {
                Snackbar.make(view!!, R.string.title_setup_imported, Snackbar.LENGTH_LONG).show()
                reload(context, "import")
            }

            override fun onException(args: Bundle?, ex: Throwable) {
                Helper.unexpectedError(context, ex)
            }
        }.load(this, args)
    }

    private fun getIntentNotifications(): Intent {
        val ctx = requireContext()
        var intent = Intent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
        }

        intent.putExtra("app_package", ctx.packageName)
            .putExtra("app_uid", ctx.applicationInfo.uid)
        return intent
    }
}
