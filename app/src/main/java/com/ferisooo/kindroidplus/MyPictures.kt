package com.ferisooo.kindroidplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The owner's own pictures for the slideshow, picked from the phone. Each is copied into the
 * app's own storage (shrunk to at most [MAX_SIDE] pixels, turned the right way up), so they
 * keep working even if the originals are deleted. The page gets them at
 * https://kindroid.ai/__kindroidplus/mine/<name>, which the app answers itself ([serve]),
 * so they never leave the phone.
 */
class MyPictures(private val activity: Activity, private val launch: (Intent, Int) -> Unit) {

    private val dir = File(activity.filesDir, "mine").apply { mkdirs() }

    /** Goes up whenever the pictures change, so the page reads the list again. */
    @Volatile var gen = 1
        private set

    fun list(): List<File> = dir.listFiles { f -> NAME.matches(f.name) }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun count() = list().size

    /** For the page: [{url, at}], newest first. */
    fun json(): String = JSONArray(list().map { JSONObject().put("url", URL_START + it.name).put("at", it.lastModified()) }).toString()

    /** The phone's photo picker (Android 13+), or its file picker on older phones. */
    fun pick() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, min(50, MediaStore.getPickImagesMaxLimit()))
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            launch(intent, PICK)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, "No app on this phone can pick pictures", Toast.LENGTH_SHORT).show()
        }
    }

    /** What the picker gave back: copied in the background. */
    fun picked(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        if (uris.isEmpty()) return
        Toast.makeText(activity, "Adding ${uris.size} picture" + if (uris.size == 1) "…" else "s…", Toast.LENGTH_SHORT).show()
        Thread {
            val added = uris.count { runCatching { copyIn(it) }.getOrDefault(false) }
            gen++
            activity.runOnUiThread {
                val failed = uris.size - added
                Toast.makeText(activity, "Added $added 💕" + if (failed > 0) " ($failed couldn't be read)" else "", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun copyIn(uri: Uri): Boolean {
        val resolver = activity.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val turn = resolver.openInputStream(uri)?.use { stream ->
            when (runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(turn) }
        val ready = if (scale < 1f || turn != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
        val file = File(dir, UUID.randomUUID().toString() + ".jpg")
        file.outputStream().use { ready.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (ready !== decoded) ready.recycle()
        decoded.recycle()
        return true
    }

    /** Answers the page's requests for /__kindroidplus/mine/<name> from the app's own storage. */
    fun serve(path: String): WebResourceResponse? {
        if (!path.startsWith(PATH_START)) return null
        val name = path.removePrefix(PATH_START)
        val file = File(dir, name)
        if (!NAME.matches(name) || !file.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        }
        return WebResourceResponse(
            "image/jpeg", null, 200, "OK",
            mapOf("Cache-Control" to "max-age=31536000", "Access-Control-Allow-Origin" to "*"),
            FileInputStream(file),
        )
    }

    /** A grid of the pictures: tap one to take it out, or take them all out. */
    fun manage() {
        val files = list().toMutableList()
        if (files.isEmpty()) {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📱 My pictures")
                .setMessage("No pictures yet. Add some from your phone, and they'll take turns behind the chat too.")
                .setPositiveButton("Add pictures") { _, _ -> pick() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val thumbs = LruCache<String, Bitmap>(60)
        val loader = Executors.newSingleThreadExecutor()
        val size = (activity.resources.displayMetrics.widthPixels / 4)
        lateinit var adapter: BaseAdapter
        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(position: Int) = files[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = (convertView as? ImageView) ?: ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                }
                val file = files[position]
                view.tag = file.name
                val cached = thumbs.get(file.name)
                view.setImageBitmap(cached)
                if (cached == null) loader.execute {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val bmp = runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull() ?: return@execute
                    thumbs.put(file.name, bmp)
                    activity.runOnUiThread { if (view.tag == file.name) view.setImageBitmap(bmp) }
                }
                return view
            }
        }
        val grid = GridView(activity).apply {
            numColumns = 3
            horizontalSpacing = 6
            verticalSpacing = 6
            setPadding(12, 12, 12, 12)
            this.adapter = adapter
        }
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("📱 My pictures · tap one to take it out")
            .setView(grid)
            .setPositiveButton("Add more") { _, _ -> pick() }
            .setNeutralButton("Take all out") { _, _ ->
                AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Take all ${files.size} pictures out of the slideshow? (The ones on your phone stay.)")
                    .setPositiveButton("Take them out") { _, _ -> list().forEach { it.delete() }; gen++ }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { loader.shutdownNow() }
        grid.setOnItemClickListener { _, _, position, _ ->
            val file = files.getOrNull(position) ?: return@setOnItemClickListener
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Take this picture out of the slideshow? (The one on your phone stays.)")
                .setPositiveButton("Take it out") { _, _ ->
                    file.delete()
                    files.remove(file)
                    gen++
                    adapter.notifyDataSetChanged()
                    if (files.isEmpty()) dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show()
    }

    companion object {
        const val PICK = 2
        private const val MAX_SIDE = 2048
        private const val PATH_START = "/__kindroidplus/mine/"
        private const val URL_START = "https://kindroid.ai$PATH_START"
        private val NAME = Regex("[0-9a-f-]{36}\\.jpg")
    }
}
