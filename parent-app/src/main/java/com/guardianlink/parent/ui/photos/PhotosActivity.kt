package com.guardianlink.parent.ui.photos

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.guardianlink.parent.R
import com.guardianlink.parent.data.firebase.ParentFirebaseManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class PhotosActivity : AppCompatActivity() {
    @Inject lateinit var firebaseManager: ParentFirebaseManager

    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photos)
        title = "Photos"
        list = findViewById(R.id.photo_list)
        progress = findViewById(R.id.photo_progress)
        loadPhotos(intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty())
    }

    private fun loadPhotos(deviceId: String) {
        if (deviceId.isBlank()) {
            showMessage("Select a child device first")
            return
        }
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val photos = firebaseManager.listPhotos(deviceId)
                if (photos.isEmpty()) {
                    showMessage("No photos yet")
                    return@launch
                }
                photos.forEach { photo -> addPhoto(photo) }
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not load photos")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private suspend fun addPhoto(photo: com.guardianlink.common.model.PhotoMetadata) {
        val bytes = withContext(Dispatchers.IO) {
            firebaseManager.downloadPhoto(photo.deviceId, photo.filename)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = photo.filename
            setPadding(0, 12, 0, 4)
        }
        val label = TextView(this).apply {
            text = photo.filename
            setPadding(0, 0, 0, 16)
        }
        list.addView(image)
        list.addView(label)
    }

    private fun showMessage(message: String) {
        if (list.childCount == 0) {
            val empty = TextView(this).apply { text = message }
            list.addView(empty)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
