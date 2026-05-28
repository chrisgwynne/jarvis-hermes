package com.jarvis.hermes.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import androidx.core.content.ContextCompat
import com.jarvis.hermes.LocalResponse

/**
 * Contacts action handler: lookup, call, add, delete, list.
 *
 * Contact name matching is greedy ("John Smith", "Mum Mobile"). Where
 * possible we score multi-match results by starred/times-contacted so
 * heavy contacts win over alphabetical first-hits.
 */
object ContactsAction {

    private const val ACTION_SHOW = "show"
    private const val ACTION_CALL = "call"
    private const val ACTION_ADD = "add"
    private const val ACTION_DELETE = "delete"
    private const val ACTION_LIST = "list"

    fun requiredPermissions() = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            Regex("""^(show|what('?s| is)|get)\s+(.+?)('?s)?\s+(number|phone)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^(show|what('?s| is)|get)\s+(.+?)('?s)?\s+(number|phone)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SHOW, "contact" to (m?.groupValues?.get(3)?.trim() ?: ""))
            }
            Regex("""^add\s+contact\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^add\s+contact\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                val rest = m?.groupValues?.get(1)?.trim() ?: ""
                val numRx = Regex("""\b(\+?\d[\d\s\-()]{5,})$""")
                val numberMatch = numRx.find(rest)
                if (numberMatch != null) {
                    val number = numberMatch.value.replace(Regex("[^0-9+]"), "")
                    val name = rest.substring(0, numberMatch.range.first).trim()
                    mapOf("action" to ACTION_ADD, "name" to name, "number" to number)
                } else {
                    mapOf("action" to ACTION_ADD, "name" to rest, "number" to "")
                }
            }
            Regex("""^delete\s+contact\s+(.+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val m = Regex("""^delete\s+contact\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_DELETE, "contact" to (m?.groupValues?.get(1)?.trim() ?: ""))
            }
            Regex("""^(show|list|open)\s+contacts$""", RegexOption.IGNORE_CASE).matches(text) ->
                mapOf("action" to ACTION_LIST)
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Contacts action unclear.", "contacts_error")

        // LIST is fine without permission — it opens the contacts UI.
        if (action == ACTION_LIST) return openContactsUi(context)

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return LocalResponse("Contacts permission not granted.", "contacts_permission")

        return when (action) {
            ACTION_SHOW -> showNumber(context, params["contact"] ?: "")
            ACTION_CALL -> {
                val name = params["contact"].orEmpty()
                if (name.isBlank()) return LocalResponse("Who to call?", "contacts_call")
                // Delegate to PhoneAction for actual call mechanics.
                PhoneAction.execute(context, mapOf("action" to "call", "target" to name, "dialOnly" to "false"))
            }
            ACTION_ADD -> addContact(context, params["name"] ?: "", params["number"] ?: "")
            ACTION_DELETE -> deleteContact(context, params["contact"] ?: "")
            else -> LocalResponse("Unknown contacts action.", "contacts_error")
        }
    }

    private fun showNumber(context: Context, name: String): LocalResponse {
        if (name.isBlank()) return LocalResponse("Which contact?", "contacts_show")
        val number = getBestNumber(context, name)
            ?: return LocalResponse("Couldn't find $name.", "contacts_error")
        return LocalResponse("$name's number is $number.", "contacts_show",
            mapOf("name" to name, "number" to number))
    }

    private fun openContactsUi(context: Context): LocalResponse {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = ContactsContract.Contacts.CONTENT_URI
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening contacts.", "contacts_list")
        } catch (_: Exception) {
            LocalResponse("Couldn't open contacts.", "contacts_error")
        }
    }

    private fun addContact(context: Context, name: String, number: String): LocalResponse {
        if (name.isBlank()) return LocalResponse("What's the contact's name?", "contacts_add")

        // Prefer the system "Insert contact" intent — it picks the account
        // automatically and gives the user a chance to confirm. This avoids
        // creating "phantom" raw contacts with no account.
        return try {
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, name)
                if (number.isNotBlank()) {
                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalResponse("Opening contacts to add $name.", "contacts_added",
                mapOf("name" to name, "number" to number))
        } catch (_: Exception) {
            // Last-ditch: direct insert via ContentProvider.
            try {
                val ops = ArrayList<ContentProviderOperation>()
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
                if (number.isNotBlank()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.Phone.NUMBER, number)
                        .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE).build())
                }
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                LocalResponse("Added $name.", "contacts_added")
            } catch (_: Exception) {
                LocalResponse("Couldn't add contact.", "contacts_error")
            }
        }
    }

    private fun deleteContact(context: Context, name: String): LocalResponse {
        if (name.isBlank()) return LocalResponse("Which contact to delete?", "contacts_delete")
        val uri = getContactUri(context, name) ?: return LocalResponse("Couldn't find $name.", "contacts_error")
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) LocalResponse("Deleted $name.", "contacts_deleted")
            else LocalResponse("Couldn't delete $name.", "contacts_error")
        } catch (_: Exception) {
            LocalResponse("Couldn't delete contact.", "contacts_error")
        }
    }

    private fun getBestNumber(context: Context, name: String): String? {
        val cursor = context.contentResolver.query(
            CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                CommonDataKinds.Phone.NUMBER,
                CommonDataKinds.Phone.STARRED,
                CommonDataKinds.Phone.TIMES_CONTACTED
            ),
            "${CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            var best: String? = null
            var bestScore = -1L
            do {
                val n = it.getString(0)?.replace(Regex("[^0-9+]"), "") ?: continue
                val score = it.getInt(1) * 1_000L + it.getInt(2)
                if (score > bestScore) { bestScore = score; best = n }
            } while (it.moveToNext())
            return best
        }
    }

    private fun getContactUri(context: Context, name: String): Uri? {
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val lookupKey = it.getString(0) ?: return null
            return Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        }
    }
}
