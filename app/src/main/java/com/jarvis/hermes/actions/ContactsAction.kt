package com.jarvis.hermes.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import androidx.core.app.ActivityCompat
import com.jarvis.hermes.LocalResponse

/**
 * Contacts action handler: show contact, call contact, add contact.
 */
object ContactsAction {

    private const val ACTION_SHOW = "show"
    private const val ACTION_CALL = "call"
    private const val ACTION_ADD = "add"
    private const val ACTION_EDIT = "edit"
    private const val ACTION_DELETE = "delete"
    private const val ACTION_LIST = "list"

    fun requiredPermissions() = listOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // "show John's number" or "what's John's number"
            Regex("""^(show|what('?s| is)|get)\s+(.+?)?\s*(number|phone)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^(show|what('?s| is)|get)\s+(.+?)?\s*(number|phone)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_SHOW, "contact" to (match?.groupValues?.get(3) ?: ""))
            }
            // "call John" (without "dial" prefix - handled by PhoneAction if "dial" prefix)
            Regex("""^call\s+(\w+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^call\s+(\w+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_CALL, "contact" to (match?.groupValues?.get(1) ?: ""))
            }
            // "add contact John" or "add contact name John number 5551234"
            Regex("""^add\s+contact\s+(\w+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^add\s+contact\s+(\w+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_ADD, "name" to (match?.groupValues?.get(1) ?: ""))
            }
            Regex("""^add\s+contact\s+name\s+(\w+)\s*.*$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^add\s+contact\s+name\s+(\w+)\s*(?:number\s+)?(\d+)?$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_ADD, "name" to (match?.groupValues?.get(1) ?: ""), "number" to (match?.groupValues?.get(2) ?: ""))
            }
            // "delete contact John"
            Regex("""^delete\s+contact\s+(\w+)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                val match = Regex("""^delete\s+contact\s+(\w+)$""", RegexOption.IGNORE_CASE).find(text)
                mapOf("action" to ACTION_DELETE, "contact" to (match?.groupValues?.get(1) ?: ""))
            }
            // "show contacts" or "list contacts"
            Regex("""^(show|list)\s*contacts$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LIST)
            }
            // "open contacts"
            Regex("""^open\s+contacts$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LIST)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Contacts action unclear.", "contacts_error")

        val missingPerms = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            return LocalResponse("Contacts permission not granted.", "contacts_permission")
        }

        return when (action) {
            ACTION_SHOW -> {
                val contactName = params["contact"] ?: ""
                if (contactName.isBlank()) {
                    return LocalResponse("Which contact's number?", "contacts_show")
                }
                val number = getContactNumber(context, contactName)
                if (number != null) {
                    LocalResponse("$contactName's number is $number.", "contacts_show", 
                        mapOf("name" to contactName, "number" to number))
                } else {
                    LocalResponse("Couldn't find $contactName.", "contacts_error")
                }
            }
            ACTION_CALL -> {
                val contactName = params["contact"] ?: ""
                if (contactName.isBlank()) {
                    return LocalResponse("Who would you like to call?", "contacts_call")
                }
                val number = getContactNumber(context, contactName)
                if (number != null) {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:$number")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        LocalResponse("Calling $contactName.", "contacts_call")
                    } catch (e: Exception) {
                        LocalResponse("Couldn't open dialer.", "contacts_error")
                    }
                } else {
                    LocalResponse("Couldn't find $contactName.", "contacts_error")
                }
            }
            ACTION_ADD -> {
                val name = params["name"] ?: ""
                val number = params["number"] ?: ""
                if (name.isBlank()) {
                    return LocalResponse("What is the contact's name?", "contacts_add")
                }
                try {
                    val operations = ArrayList<ContentProviderOperation>()
                    operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build())
                    operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build())
                    if (number.isNotBlank()) {
                        operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(CommonDataKinds.Phone.NUMBER, number)
                            .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
                            .build())
                    }
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                    LocalResponse("Added contact $name.", "contacts_added", mapOf("name" to name, "number" to number))
                } catch (e: Exception) {
                    LocalResponse("Couldn't add contact.", "contacts_error")
                }
            }
            ACTION_DELETE -> {
                val contactName = params["contact"] ?: ""
                if (contactName.isBlank()) {
                    return LocalResponse("Which contact to delete?", "contacts_delete")
                }
                val uri = getContactUri(context, contactName)
                if (uri != null) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                        LocalResponse("Deleted $contactName.", "contacts_deleted")
                    } catch (e: Exception) {
                        LocalResponse("Couldn't delete contact.", "contacts_error")
                    }
                } else {
                    LocalResponse("Couldn't find $contactName.", "contacts_error")
                }
            }
            ACTION_LIST -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = ContactsContract.Contacts.CONTENT_URI
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    LocalResponse("Opening contacts.", "contacts_list")
                } catch (e: Exception) {
                    LocalResponse("Couldn't open contacts.", "contacts_error")
                }
            }
            else -> LocalResponse("Unknown contacts action.", "contacts_error")
        }
    }

    private fun getContactNumber(context: Context, name: String): String? {
        val cursor = context.contentResolver.query(
            CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(CommonDataKinds.Phone.NUMBER),
            "${CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)?.replace(Regex("[^0-9+]"), "")
            }
        }
        return null
    }

    private fun getContactUri(context: Context, name: String): Uri? {
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getString(0)
                return Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id)
            }
        }
        return null
    }
}
