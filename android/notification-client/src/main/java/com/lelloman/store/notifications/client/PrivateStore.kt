package com.lelloman.store.notifications.client

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Installation state lives exclusively in noBackupFilesDir. */
class PrivateStore(context: Context, name: String) : SQLiteOpenHelper(object : ContextWrapper(context) {
    override fun getDatabasePath(name: String): File = File(noBackupFilesDir, name)
}, "$name.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, body TEXT NOT NULL, state TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE settings (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun setting(id: String): String? = readableDatabase.rawQuery("SELECT body FROM settings WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun setting(id: String, body: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf(id, body)) }
    @Synchronized fun entry(id: String): Pair<JSONObject, String>? = readableDatabase.rawQuery("SELECT body,state FROM entries WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) to it.getString(1) else null }
    @Synchronized fun put(id: String, body: JSONObject, state: String = "pending") {
        writableDatabase.execSQL("INSERT OR REPLACE INTO entries VALUES(?,?,?,?)", arrayOf(id, body.toString(), state, System.currentTimeMillis()))
    }
    @Synchronized fun state(id: String, state: String) { writableDatabase.execSQL("UPDATE entries SET state=? WHERE id=?", arrayOf(state, id)) }
    @Synchronized fun entries(state: String? = null): List<Pair<String, JSONObject>> = readableDatabase.rawQuery(
        "SELECT id,body FROM entries" + if (state == null) " ORDER BY at" else " WHERE state=? ORDER BY at", state?.let { arrayOf(it) },
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to JSONObject(c.getString(1))) } }
    @Synchronized fun remove(id: String) { writableDatabase.delete("entries", "id=?", arrayOf(id)) }
    @Synchronized fun clear() { writableDatabase.execSQL("DELETE FROM entries"); writableDatabase.execSQL("DELETE FROM settings") }
    @Synchronized fun prune() { writableDatabase.execSQL("DELETE FROM entries WHERE state<>'pending' AND at<?", arrayOf(System.currentTimeMillis() - 30L * 86400 * 1000)) }
}

/** Keystore-protected credentials, not included in cloud backup or device transfer. */
class CredentialFile(context: Context, name: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.secret"))
    private val alias = "${context.packageName}.$name"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = file.startWrite()
        try { out.write(cipher.iv + cipher.doFinal(value.toString().toByteArray())); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
